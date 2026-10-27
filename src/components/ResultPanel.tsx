import { useMemo, useState } from 'react'
import type { CompressResult } from '../types'
import { formatBytes } from '../lib/format'
import { native, saveOutputNative } from '../lib/native'

interface ResultPanelProps {
  result: CompressResult
  originalSize: number
  originalName: string
}

function outputFileName(originalName: string): string {
  const base = originalName.replace(/\.[^/.]+$/, '') || 'video'
  return `${base}-compressed.mp4`
}

export function ResultPanel({
  result,
  originalSize,
  originalName,
}: ResultPanelProps) {
  const [shareError, setShareError] = useState<string | null>(null)
  const [nativeBusy, setNativeBusy] = useState(false)
  const [nativeSaved, setNativeSaved] = useState(false)
  const fileName = useMemo(
    () => outputFileName(originalName),
    [originalName],
  )
  const reduction = originalSize
    ? Math.round((1 - result.size / originalSize) * 100)
    : 0

  const canShareFiles =
    typeof navigator !== 'undefined' &&
    'canShare' in navigator &&
    (() => {
      try {
        const probe = new File([new Uint8Array(1)], fileName, {
          type: 'video/mp4',
        })
        return navigator.canShare?.({ files: [probe] }) ?? false
      } catch {
        return false
      }
    })()

  // Android app: the shell saves to the gallery once, then shares that copy.
  const handleNative = async (share: boolean) => {
    if (!native) return
    if (share && nativeSaved) {
      native.shareSaved()
      return
    }
    setNativeBusy(true)
    try {
      if (await saveOutputNative(result.blob, fileName, share)) {
        setNativeSaved(true)
      }
    } finally {
      setNativeBusy(false)
    }
  }

  const handleShare = async () => {
    setShareError(null)
    try {
      const file = new File([result.blob], fileName, { type: 'video/mp4' })
      await navigator.share({
        files: [file],
        title: 'Compressed video',
      })
    } catch (err) {
      if (err instanceof Error && err.name !== 'AbortError') {
        setShareError('Could not open the share sheet.')
      }
    }
  }

  return (
    <div className="w-full rounded-2xl border border-slate-200 dark:border-slate-800 p-5">
      <p className="font-medium text-slate-700 dark:text-slate-200">
        Ready to share
      </p>

      <video
        src={result.url}
        controls
        playsInline
        className="mt-3 w-full rounded-xl bg-black max-h-80"
      />

      <div className="mt-4 flex items-center justify-between rounded-xl bg-slate-100 dark:bg-slate-800/60 px-4 py-3 text-sm">
        <div>
          <p className="text-slate-400 dark:text-slate-500">Original</p>
          <p className="font-medium text-slate-700 dark:text-slate-200">
            {formatBytes(originalSize)}
          </p>
        </div>
        <div className="text-2xl text-slate-300 dark:text-slate-600">
          &rarr;
        </div>
        <div className="text-right">
          <p className="text-slate-400 dark:text-slate-500">Compressed</p>
          <p className="font-medium text-emerald-600 dark:text-emerald-400">
            {formatBytes(result.size)}
          </p>
        </div>
        {reduction > 0 && (
          <div className="ml-2 rounded-full bg-emerald-500/10 px-2.5 py-1 text-xs font-medium text-emerald-600 dark:text-emerald-400">
            -{reduction}%
          </div>
        )}
      </div>

      {native && (
        <div className="mt-4 flex gap-3">
          <button
            type="button"
            onClick={() => handleNative(false)}
            disabled={nativeBusy || nativeSaved}
            className="flex-1 rounded-xl bg-indigo-600 px-4 py-3 font-medium text-white transition-colors hover:bg-indigo-500 disabled:bg-slate-300 dark:disabled:bg-slate-700"
          >
            {nativeBusy ? 'Saving…' : nativeSaved ? 'Saved' : 'Save to Photos'}
          </button>
          <button
            type="button"
            onClick={() => handleNative(true)}
            disabled={nativeBusy}
            className="flex-1 rounded-xl border border-slate-300 dark:border-slate-700 px-4 py-3 font-medium text-slate-700 dark:text-slate-200 transition-colors hover:bg-slate-100 dark:hover:bg-slate-800 disabled:opacity-50"
          >
            Share
          </button>
        </div>
      )}

      {!native && (
        <div className="mt-4 flex gap-3">
          <a
            href={result.url}
            download={fileName}
            className="flex-1 rounded-xl bg-indigo-600 px-4 py-3 text-center font-medium text-white transition-colors hover:bg-indigo-500"
          >
            Download
          </a>
          {canShareFiles && (
            <button
              type="button"
              onClick={handleShare}
              className="flex-1 rounded-xl border border-slate-300 dark:border-slate-700 px-4 py-3 font-medium text-slate-700 dark:text-slate-200 transition-colors hover:bg-slate-100 dark:hover:bg-slate-800"
            >
              Share
            </button>
          )}
        </div>
      )}
      {shareError && (
        <p className="mt-2 text-sm text-red-500 text-center">{shareError}</p>
      )}
    </div>
  )
}
