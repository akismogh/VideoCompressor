import { useEffect, useRef } from 'react'
import type { VideoMeta } from '../types'
import { formatTime } from '../lib/format'

interface VideoTrimmerProps {
  video: VideoMeta
  trimStart: number
  trimEnd: number
  onChange: (start: number, end: number) => void
}

const MIN_GAP = 0.5

export function VideoTrimmer({
  video,
  trimStart,
  trimEnd,
  onChange,
}: VideoTrimmerProps) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const { duration } = video

  const handleStart = (value: number) => {
    const clamped = Math.min(value, trimEnd - MIN_GAP)
    onChange(Math.max(0, clamped), trimEnd)
    if (videoRef.current) videoRef.current.currentTime = clamped
  }

  const handleEnd = (value: number) => {
    const clamped = Math.max(value, trimStart + MIN_GAP)
    onChange(trimStart, Math.min(duration, clamped))
    if (videoRef.current) videoRef.current.currentTime = clamped
  }

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

  const startPct = (trimStart / duration) * 100
  const endPct = (trimEnd / duration) * 100

  return (
    <div className="w-full">
      <video
        ref={videoRef}
        src={video.url}
        controls
        playsInline
        className="w-full rounded-xl bg-black max-h-80"
        onLoadedMetadata={(e) => {
          e.currentTarget.currentTime = trimStart
        }}
      />

      <div className="mt-4 flex items-center justify-between text-sm text-slate-500 dark:text-slate-400">
        <span>{formatTime(trimStart)}</span>
        <span className="font-medium text-slate-700 dark:text-slate-200">
          Trimmed length: {formatTime(trimEnd - trimStart)}
        </span>
        <span>{formatTime(trimEnd)}</span>
      </div>

      <div className="relative mt-2 h-8">
        <div className="absolute top-1/2 -translate-y-1/2 w-full h-1.5 rounded-full bg-slate-200 dark:bg-slate-700" />
        <div
          className="absolute top-1/2 -translate-y-1/2 h-1.5 rounded-full bg-indigo-500"
          style={{ left: `${startPct}%`, right: `${100 - endPct}%` }}
        />
        <input
          type="range"
          min={0}
          max={duration}
          step={0.05}
          value={trimStart}
          onChange={(e) => handleStart(Number(e.target.value))}
          className="range-thumb absolute w-full top-1/2 -translate-y-1/2"
        />
        <input
          type="range"
          min={0}
          max={duration}
          step={0.05}
          value={trimEnd}
          onChange={(e) => handleEnd(Number(e.target.value))}
          className="range-thumb absolute w-full top-1/2 -translate-y-1/2"
        />
      </div>
    </div>
  )
}
