import { useEffect, useRef, useState } from 'react'
import type { VideoMeta } from '../types'
import { formatBytes, formatTime, formatTimePrecise } from '../lib/format'
import { Gauge } from './Gauge'
import { CircleButton, Heading, Icon, PillButton, ToggleTile } from './ui'

const MIN_GAP = 0.5
// Smart Compress targets: 1500 kbps video + 128 kbps AAC.
const VIDEO_BPS = 1_500_000
const AUDIO_BPS = 128_000

interface EditScreenProps {
  video: VideoMeta
  trimStart: number
  trimEnd: number
  onTrimChange: (start: number, end: number) => void
  mute: boolean
  onMuteChange: (mute: boolean) => void
  onCompress: () => void
  processing: boolean
  busyLabel: string
  progress: number
  error: string | null
  onBack: () => void
  /** Capture again from the same source; absent when the source isn't known. */
  redo: { label: string; onClick: () => void } | null
}

export function EditScreen({
  video,
  trimStart,
  trimEnd,
  onTrimChange,
  mute,
  onMuteChange,
  onCompress,
  processing,
  busyLabel,
  progress,
  error,
  onBack,
  redo,
}: EditScreenProps) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const { duration } = video
  const length = Math.max(0, trimEnd - trimStart)
  const estimate = Math.min(
    video.file.size,
    ((VIDEO_BPS + (mute ? 0 : AUDIO_BPS)) * length) / 8,
  )

  const [playing, setPlaying] = useState(false)
  const [current, setCurrent] = useState(0)
  // Playback that starts inside the selection previews it and stops at the trim
  // end; playback started outside it runs freely so you can look for new points.
  const stopAtEnd = useRef(false)

  useEffect(() => {
    const el = videoRef.current
    if (!el || !playing) return
    let raf = 0
    const tick = () => {
      if (stopAtEnd.current && el.currentTime >= trimEnd) {
        el.pause()
        el.currentTime = trimEnd
        setCurrent(trimEnd)
        return
      }
      setCurrent(el.currentTime)
      raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [playing, trimEnd])

  const inSelection = (t: number) => t >= trimStart && t < trimEnd

  const seek = (t: number) => {
    const el = videoRef.current
    if (!el) return
    el.currentTime = t
    setCurrent(t)
    stopAtEnd.current = inSelection(t)
  }

  const togglePlay = () => {
    const el = videoRef.current
    if (!el) return
    if (!el.paused) {
      el.pause()
      return
    }
    // Sitting at the trim end (after a preview) or the very end: replay the selection.
    if (Math.abs(el.currentTime - trimEnd) < 0.1 || el.currentTime >= duration - 0.05) {
      el.currentTime = trimStart
    }
    stopAtEnd.current = inSelection(el.currentTime)
    void el.play()
  }

  const setStart = (value: number) => {
    const start = Math.max(0, Math.min(value, trimEnd - MIN_GAP))
    onTrimChange(start, trimEnd)
    seek(start)
  }

  const setEnd = (value: number) => {
    const end = Math.min(duration, Math.max(value, trimStart + MIN_GAP))
    onTrimChange(trimStart, end)
    seek(end)
  }

  const playhead = () => videoRef.current?.currentTime ?? current
  const startPct = (trimStart / duration) * 100
  const endPct = (trimEnd / duration) * 100

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-center justify-between">
        <CircleButton icon="back" label="Back" onClick={onBack} disabled={processing} />
        <span className="glass-strong rounded-full px-4 py-2 text-sm text-muted">
          {video.width}×{video.height} · {formatBytes(video.file.size)}
        </span>
      </div>

      <div className="flex items-end justify-between gap-3">
        <Heading light="Your" bold="Clip" />
        {redo && (
          <button
            type="button"
            onClick={redo.onClick}
            disabled={processing}
            className="mb-1 flex h-14 shrink-0 items-center gap-2 rounded-full bg-accent pr-5 pl-2 text-ink transition active:scale-95 disabled:opacity-40"
          >
            <span className="flex h-10 w-10 items-center justify-center rounded-full bg-ink text-accent">
              <Icon name="redo" className="h-5 w-5" />
            </span>
            <span className="text-[0.95rem] font-medium">{redo.label}</span>
          </button>
        )}
      </div>

      <div className="relative overflow-hidden rounded-[1.75rem] bg-black shadow-xl">
        <video
          ref={videoRef}
          src={video.url}
          playsInline
          className="max-h-[45vh] w-full"
          onClick={togglePlay}
          onPlay={() => setPlaying(true)}
          onPause={() => setPlaying(false)}
          onEnded={() => setPlaying(false)}
          onSeeked={(e) => setCurrent(e.currentTarget.currentTime)}
        />
        {!playing && (
          <span className="pointer-events-none absolute inset-0 flex items-center justify-center">
            <span className="flex h-16 w-16 items-center justify-center rounded-full bg-accent/90 text-ink">
              <Icon name="play" className="h-7 w-7 translate-x-0.5" />
            </span>
          </span>
        )}
      </div>

      {processing ? (
        <div className="glass rounded-[1.75rem] px-4 pt-4 pb-6">
          <Gauge value={progress}>
            <span className="text-5xl font-light tabular-nums">
              {Math.round(progress * 100)}
              <span className="align-top text-2xl">%</span>
            </span>
            <span className="text-sm text-muted">{busyLabel.replace('…', '')}</span>
          </Gauge>
          <p className="text-center text-xs text-faint">
            Keep the app open — compression runs on your phone.
          </p>
        </div>
      ) : (
        <>
          <section className="glass rounded-[1.75rem] p-4">
            <div className="flex items-center gap-3">
              <CircleButton
                icon={playing ? 'pause' : 'play'}
                label={playing ? 'Pause' : 'Play'}
                onClick={togglePlay}
                accent
                size="h-12 w-12"
              />
              <span className="text-muted tabular-nums">
                <span className="text-2xl font-light text-white">{formatTimePrecise(current)}</span>{' '}
                / {formatTime(duration)}
              </span>
            </div>

            {/* Playhead: free to move over the whole clip; the band marks the selection. */}
            <div className="relative mt-3 h-9">
              <div className="absolute inset-x-[13px] top-1/2 h-1.5 -translate-y-1/2">
                <div className="absolute inset-0 rounded-full bg-white/15" />
                <div
                  className="absolute inset-y-0 bg-accent/40"
                  style={{ left: `${startPct}%`, right: `${100 - endPct}%` }}
                />
              </div>
              <input
                type="range"
                aria-label="Playhead"
                min={0}
                max={duration}
                step={0.01}
                value={current}
                onChange={(e) => seek(Number(e.target.value))}
                className="scrub absolute top-1/2 w-full -translate-y-1/2"
              />
            </div>

            <div className="mt-4 flex items-baseline justify-between">
              <h2 className="text-xl">Trim</h2>
              <span className="text-muted">
                <span className="text-2xl font-light text-white tabular-nums">{formatTimePrecise(length)}</span>{' '}
                selected
              </span>
            </div>

            <div className="relative mt-2 h-9">
              {/* Inset by half a thumb so the fill ends exactly under each thumb's centre. */}
              <div className="absolute inset-x-[14px] top-1/2 h-2 -translate-y-1/2">
                <div className="absolute inset-0 rounded-full bg-white/15" />
                <div
                  className="absolute inset-y-0 rounded-full bg-accent"
                  style={{ left: `${startPct}%`, right: `${100 - endPct}%` }}
                />
              </div>
              <input
                type="range"
                aria-label="Trim start"
                min={0}
                max={duration}
                step={0.05}
                value={trimStart}
                onChange={(e) => setStart(Number(e.target.value))}
                className="range-thumb absolute top-1/2 w-full -translate-y-1/2"
              />
              <input
                type="range"
                aria-label="Trim end"
                min={0}
                max={duration}
                step={0.05}
                value={trimEnd}
                onChange={(e) => setEnd(Number(e.target.value))}
                className="range-thumb absolute top-1/2 w-full -translate-y-1/2"
              />
            </div>
            <div className="mt-1 flex justify-between text-sm text-muted tabular-nums">
              <span>{formatTimePrecise(trimStart)}</span>
              <span>{formatTimePrecise(trimEnd)}</span>
            </div>

            <div className="mt-4 grid grid-cols-2 gap-3">
              <button
                type="button"
                onClick={() => setStart(playhead())}
                className="glass-strong flex h-12 items-center justify-center gap-2 rounded-full text-[0.95rem] active:scale-[0.98]"
              >
                <Icon name="markIn" className="h-5 w-5" />
                Start here
              </button>
              <button
                type="button"
                onClick={() => setEnd(playhead())}
                className="glass-strong flex h-12 items-center justify-center gap-2 rounded-full text-[0.95rem] active:scale-[0.98]"
              >
                End here
                <Icon name="markOut" className="h-5 w-5" />
              </button>
            </div>
          </section>

          <div className="grid grid-cols-2 gap-3">
            <ToggleTile icon="speaker" label="Sound" checked={!mute} onChange={(on) => onMuteChange(!on)} />
            <div className="glass flex flex-col justify-between gap-5 rounded-[1.75rem] p-3">
              <div className="flex items-center gap-2">
                <span className="glass-strong flex h-10 w-10 shrink-0 items-center justify-center rounded-full">
                  <Icon name="scale" className="h-5 w-5" />
                </span>
                <span className="min-w-0 text-[0.9rem] leading-tight">Est. size</span>
              </div>
              <div>
                <span className="block text-xl font-light whitespace-nowrap">≈ {formatBytes(estimate)}</span>
                <span className="block text-xs text-faint">720p · 30 fps</span>
              </div>
            </div>
          </div>

          {error && <p className="text-center text-sm text-[#ff8a7a]">{error}</p>}

          <PillButton accent onClick={onCompress}>
            <Icon name="bolt" />
            Compress
          </PillButton>
        </>
      )}
    </div>
  )
}
