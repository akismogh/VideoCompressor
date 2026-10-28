import { useEffect, useRef } from 'react'
import type { VideoMeta } from '../types'
import { formatBytes, formatTime } from '../lib/format'
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
}: EditScreenProps) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const { duration } = video
  const length = Math.max(0, trimEnd - trimStart)
  const estimate = Math.min(
    video.file.size,
    ((VIDEO_BPS + (mute ? 0 : AUDIO_BPS)) * length) / 8,
  )

  // Keep preview playback inside the trimmed range.
  useEffect(() => {
    const el = videoRef.current
    if (!el) return
    const onTimeUpdate = () => {
      if (el.currentTime >= trimEnd) {
        el.pause()
        el.currentTime = trimStart
      }
    }
    el.addEventListener('timeupdate', onTimeUpdate)
    return () => el.removeEventListener('timeupdate', onTimeUpdate)
  }, [trimStart, trimEnd])

  const seek = (t: number) => {
    if (videoRef.current) videoRef.current.currentTime = t
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

  const playhead = () => videoRef.current?.currentTime ?? 0
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

      <Heading light="Your" bold="Clip" />

      <div className="overflow-hidden rounded-[1.75rem] bg-black shadow-xl">
        <video
          ref={videoRef}
          src={video.url}
          controls
          playsInline
          className="max-h-[45vh] w-full"
          onLoadedMetadata={(e) => {
            e.currentTarget.currentTime = trimStart
          }}
        />
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
            <div className="flex items-baseline justify-between">
              <h2 className="text-xl">Trim</h2>
              <span className="text-muted">
                <span className="text-2xl font-light text-white tabular-nums">{formatTime(length)}</span>{' '}
                of {formatTime(duration)}
              </span>
            </div>

            <div className="relative mt-3 h-9">
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
              <span>{formatTime(trimStart)}</span>
              <span>{formatTime(trimEnd)}</span>
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
