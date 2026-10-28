import { useEffect, useState } from 'react'
import type { RecordingState } from '../lib/native'
import { formatTime } from '../lib/format'
import { Heading, Icon, ToggleTile, type IconName } from './ui'

interface HomeScreenProps {
  state: RecordingState
  loading: boolean
  error: string | null
  onRecordScreen: (deviceAudio: boolean, mic: boolean) => void
  onStopRecording: () => void
  onCamera: () => void
  onLibrary: () => void
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

function SourceTile({
  icon,
  label,
  hint,
  onClick,
}: {
  icon: IconName
  label: string
  hint: string
  onClick: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="glass flex min-h-40 flex-col justify-between rounded-[1.75rem] p-3 text-left transition active:scale-[0.97]"
    >
      <span className="flex h-14 w-14 items-center justify-center rounded-full bg-accent text-ink">
        <Icon name={icon} className="h-7 w-7" />
      </span>
      <span>
        <span className="block text-[1.05rem] font-medium leading-tight">{label}</span>
        <span className="block text-xs leading-snug text-faint">{hint}</span>
      </span>
    </button>
  )
}

export function HomeScreen({
  state,
  loading,
  error,
  onRecordScreen,
  onStopRecording,
  onCamera,
  onLibrary,
  onEndSession,
}: HomeScreenProps) {
  const [prefs, setPrefs] = useState(loadPrefs)
  const [now, setNow] = useState(() => Date.now())
  const recording = state.phase === 'recording'
  const busy = state.phase !== 'idle' || loading

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
    state.phase === 'idle' && state.sessionActive && state.sessionEndsAt
      ? new Date(state.sessionEndsAt).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
      : null

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-start justify-between gap-4">
        <Heading light="Video" bold="Compressor" sub="Capture or pick a clip, then shrink it to share." />
        <span className="flex h-14 w-14 shrink-0 items-center justify-center rounded-full bg-accent text-ink">
          <Icon name="film" className="h-6 w-6" />
        </span>
      </div>

      {busy ? (
        <BusyCard
          phase={state.phase}
          loading={loading}
          elapsed={(now - state.startedAt) / 1000}
          onStop={onStopRecording}
        />
      ) : (
        <div className="grid grid-cols-3 gap-3">
          <SourceTile
            icon="screen"
            label="Screen"
            hint="Any app"
            onClick={() => onRecordScreen(prefs.device, prefs.mic)}
          />
          <SourceTile icon="camera" label="Camera" hint="Film a clip" onClick={onCamera} />
          <SourceTile icon="library" label="Library" hint="Pick a video" onClick={onLibrary} />
        </div>
      )}

      {error && <p className="text-center text-sm text-[#ff8a7a]">{error}</p>}

      <section className="flex flex-col gap-3">
        <h2 className="px-1 text-[0.95rem] text-muted">Screen recording sound</h2>
        <div className="grid grid-cols-2 gap-3">
          <ToggleTile
            icon="speaker"
            label="Device sound"
            checked={prefs.device}
            onChange={(device) => setPrefs({ ...prefs, device })}
            disabled={busy}
          />
          <ToggleTile
            icon="mic"
            label="Microphone"
            checked={prefs.mic}
            onChange={(mic) => setPrefs({ ...prefs, mic })}
            disabled={busy}
          />
        </div>
        {sessionEnds ? (
          <p className="px-1 text-xs leading-relaxed text-faint">
            Screen sharing stays on — no permission prompt until {sessionEnds}.{' '}
            <button type="button" onClick={onEndSession} className="text-accent underline">
              End now
            </button>
          </p>
        ) : (
          <p className="px-1 text-xs leading-relaxed text-faint">
            The app steps aside while recording. Stop from the notification, the
            status-bar chip, or here. If you rotate the phone, the orientation you
            recorded longest is kept.
          </p>
        )}
      </section>
    </div>
  )
}

function BusyCard({
  phase,
  loading,
  elapsed,
  onStop,
}: {
  phase: RecordingState['phase']
  loading: boolean
  elapsed: number
  onStop: () => void
}) {
  if (phase === 'recording') {
    return (
      <div className="glass flex items-center justify-between gap-4 rounded-[1.75rem] p-5">
        <div>
          <p className="flex items-center gap-2 text-muted">
            <span className="h-2.5 w-2.5 animate-pulse rounded-full bg-[#ff5a4a]" />
            Recording screen
          </p>
          <p className="mt-1 text-6xl font-extralight tabular-nums">{formatTime(elapsed)}</p>
        </div>
        <button
          type="button"
          aria-label="Stop recording"
          onClick={onStop}
          className="flex h-20 w-20 shrink-0 items-center justify-center rounded-full bg-accent transition active:scale-95"
        >
          <span className="h-7 w-7 rounded-md bg-ink" />
        </button>
      </div>
    )
  }

  const label = loading
    ? 'Loading video…'
    : phase === 'starting'
      ? 'Waiting for permission…'
      : 'Saving recording…'
  return (
    <div className="glass flex items-center gap-4 rounded-[1.75rem] p-5">
      <span className="h-12 w-12 shrink-0 animate-spin rounded-full border-4 border-white/15 border-t-accent" />
      <p className="text-xl font-light">{label}</p>
    </div>
  )
}
