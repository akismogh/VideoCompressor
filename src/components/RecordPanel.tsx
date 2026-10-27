import { useEffect, useState } from 'react'
import type { RecordingState } from '../lib/native'
import { formatTime } from '../lib/format'

interface RecordPanelProps {
  state: RecordingState
  error: string | null
  onStart: (withAudio: boolean) => void
  onStop: () => void
}

export function RecordPanel({ state, error, onStart, onStop }: RecordPanelProps) {
  const [withAudio, setWithAudio] = useState(true)
  const [now, setNow] = useState(() => Date.now())
  const recording = state.phase === 'recording'

  useEffect(() => {
    if (!recording) return
    const id = window.setInterval(() => setNow(Date.now()), 500)
    return () => window.clearInterval(id)
  }, [recording])

  return (
    <div className="w-full rounded-2xl border border-slate-200 dark:border-slate-800 p-5">
      {state.phase === 'idle' && (
        <>
          <button
            type="button"
            onClick={() => onStart(withAudio)}
            className="flex w-full items-center justify-center gap-2 rounded-xl bg-red-600 px-4 py-3 font-medium text-white transition-colors hover:bg-red-500"
          >
            <span className="h-3 w-3 rounded-full bg-white" />
            Record screen
          </button>
          <label className="mt-3 flex items-center gap-2 text-sm text-slate-500 dark:text-slate-400 select-none">
            <input
              type="checkbox"
              checked={withAudio}
              onChange={(e) => setWithAudio(e.target.checked)}
              className="h-4 w-4 accent-indigo-500"
            />
            Include device sound
          </label>
          <p className="mt-2 text-xs text-slate-400 dark:text-slate-500">
            The app steps aside while recording. Stop from the notification,
            the status-bar chip, or by coming back here.
          </p>
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
