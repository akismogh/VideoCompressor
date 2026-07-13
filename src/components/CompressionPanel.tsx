interface CompressionPanelProps {
  mute: boolean
  onMuteChange: (mute: boolean) => void
  onCompress: () => void
  busy: boolean
  busyLabel: string
  progress: number
}

export function CompressionPanel({
  mute,
  onMuteChange,
  onCompress,
  busy,
  busyLabel,
  progress,
}: CompressionPanelProps) {
  return (
    <div className="w-full rounded-2xl border border-slate-200 dark:border-slate-800 p-5">
      <div className="flex items-center justify-between gap-4">
        <div>
          <p className="font-medium text-slate-700 dark:text-slate-200">
            Smart Compress
          </p>
          <p className="text-sm text-slate-400 dark:text-slate-500">
            720p · 30fps · 1500kbps · H.264/AAC MP4
          </p>
        </div>
        <label className="flex items-center gap-2 text-sm text-slate-500 dark:text-slate-400 select-none">
          <input
            type="checkbox"
            checked={mute}
            onChange={(e) => onMuteChange(e.target.checked)}
            className="h-4 w-4 accent-indigo-500"
          />
          Mute audio
        </label>
      </div>

      <button
        type="button"
        onClick={onCompress}
        disabled={busy}
        className="mt-4 w-full rounded-xl bg-indigo-600 px-4 py-3 font-medium text-white transition-colors hover:bg-indigo-500 disabled:cursor-not-allowed disabled:bg-slate-300 dark:disabled:bg-slate-700"
      >
        {busy ? busyLabel : 'Compress video'}
      </button>

      {busy && (
        <div className="mt-3">
          <div className="h-2 w-full overflow-hidden rounded-full bg-slate-200 dark:bg-slate-700">
            <div
              className="h-full rounded-full bg-indigo-500 transition-[width] duration-150"
              style={{ width: `${Math.round(progress * 100)}%` }}
            />
          </div>
          <p className="mt-1 text-right text-xs text-slate-400">
            {Math.round(progress * 100)}%
          </p>
        </div>
      )}
    </div>
  )
}
