import { useEffect, useState } from 'react'
import type { RecordingState } from '../lib/native'
import { formatTime } from '../lib/format'

interface RecordPanelProps {
  state: RecordingState
  error: string | null
  onStart: (deviceAudio: boolean, mic: boolean) => void
  onStop: () => void
  onEndSession: () => void
}

const PREFS_KEY = 'videoCompressor.recordAudio'

function loadPrefs(): { device: boolean; mic: boolean } {
  try {
    const saved = JSON.parse(localStorage.getItem(PREFS_KEY) ?? 'null')
    if (saved && typeof saved.device === 'boolean' && typeof saved.mic === 'boolean') {
      return saved
    }
  } catch {
    // ignore unreadable storage
  }
  return { device: true, mic: false }
}

export function RecordPanel({
  state,
  error,
  onStart,
  onStop,
  onEndSession,
}: RecordPanelProps) {
  const [prefs, setPrefs] = useState(loadPrefs)
  const [now, setNow] = useState(() => Date.now())
  const recording = state.phase === 'recording'

  useEffect(() => {
    try {
      localStorage.setItem(PREFS_KEY, JSON.stringify(prefs))
    } catch {
      // per-device convenience only
    }
  }, [prefs])

  useEffect(() => {
    if (!recording) return
    const id = window.setInterval(() => setNow(Date.now()), 500)
    return () => window.clearInterval(id)
  }, [recording])

  const sessionEnds =
    state.sessionActive && state.sessionEndsAt
      ? new Date(state.sessionEndsAt).toLocaleTimeString([], {
          hour: 'numeric',
          minute: '2-digit',
        })
      : null

  return (
    <div className="w-full rounded-2xl border border-slate-200 dark:border-slate-800 p-5">
      {state.phase === 'idle' && (
        <>
          <button
            type="button"
            onClick={() => onStart(prefs.device, prefs.mic)}
            className="flex w-full items-center justify-center gap-2 rounded-xl bg-red-600 px-4 py-3 font-medium text-white transition-colors hover:bg-red-500"
          >
            <span className="h-3 w-3 rounded-full bg-white" />
            Record screen
          </button>
          <div className="mt-3 flex flex-wrap gap-x-5 gap-y-2 text-sm text-slate-500 dark:text-slate-400 select-none">
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={prefs.device}
                onChange={(e) => setPrefs({ ...prefs, device: e.target.checked })}
                className="h-4 w-4 accent-indigo-500"
              />
              Device sound
            </label>
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={prefs.mic}
                onChange={(e) => setPrefs({ ...prefs, mic: e.target.checked })}
                className="h-4 w-4 accent-indigo-500"
              />
              Microphone
            </label>
          </div>
          {sessionEnds ? (
            <p className="mt-2 text-xs text-slate-400 dark:text-slate-500">
              Screen sharing stays on, so there's no permission prompt until{' '}
              {sessionEnds}.{' '}
              <button
                type="button"
                onClick={onEndSession}
                className="underline hover:text-slate-600 dark:hover:text-slate-300"
              >
                End now
              </button>
            </p>
          ) : (
            <p className="mt-2 text-xs text-slate-400 dark:text-slate-500">
              The app steps aside while recording. Stop from the notification,
              the status-bar chip, or by coming back here. If you rotate the
              phone, the part in the orientation you recorded longest is kept.
            </p>
          )}
        </>
      )}

      {state.phase === 'starting' && (
        <button
          type="button"
          disabled
          className="w-full rounded-xl bg-slate-300 dark:bg-slate-700 px-4 py-3 font-medium text-white"
        >
          Waiting for permission…
        </button>
      )}

      {recording && (
        <button
          type="button"
          onClick={onStop}
          className="flex w-full items-center justify-center gap-2 rounded-xl bg-red-600 px-4 py-3 font-medium text-white transition-colors hover:bg-red-500"
        >
          <span className="h-3 w-3 animate-pulse rounded-sm bg-white" />
          Stop recording · {formatTime((now - state.startedAt) / 1000)}
        </button>
      )}

      {state.phase === 'finishing' && (
        <button
          type="button"
          disabled
          className="w-full rounded-xl bg-slate-300 dark:bg-slate-700 px-4 py-3 font-medium text-white"
        >
          Saving recording…
        </button>
      )}

      {error && <p className="mt-3 text-sm text-red-500 text-center">{error}</p>}
    </div>
  )
}
