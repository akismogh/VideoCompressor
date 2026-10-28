import { useMemo, useState } from 'react'
import type { CompressResult } from '../types'
import { formatBytes } from '../lib/format'
import { native, saveOutputNative } from '../lib/native'
import { Gauge } from './Gauge'
import { CircleButton, Heading, Icon, PillButton } from './ui'

interface ResultScreenProps {
  result: CompressResult
  originalSize: number
  originalName: string
  onBack: () => void
  onNew: () => void
}

function outputFileName(originalName: string): string {
  const base = originalName.replace(/\.[^/.]+$/, '') || 'video'
  return `${base}-compressed.mp4`
}

export function ResultScreen({ result, originalSize, originalName, onBack, onNew }: ResultScreenProps) {
  const [busy, setBusy] = useState(false)
  const [saved, setSaved] = useState(false)
  const fileName = useMemo(() => outputFileName(originalName), [originalName])
  const saving = originalSize > 0 ? 1 - result.size / originalSize : 0

  // The app shell saves to the gallery once, then shares that copy.
  const save = async (share: boolean) => {
    if (!native) return
    if (share && saved) {
      native.shareSaved()
      return
    }
    setBusy(true)
    try {
      if (await saveOutputNative(result.blob, fileName, share)) setSaved(true)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-center justify-between">
        <CircleButton icon="back" label="Back to editing" onClick={onBack} />
        <CircleButton icon="plus" label="New video" onClick={onNew} />
      </div>

      <Heading light="Ready to" bold="Share" />

      <div className="overflow-hidden rounded-[1.75rem] bg-black shadow-xl">
        <video src={result.url} controls playsInline className="max-h-[40vh] w-full" />
      </div>

      <div className="glass rounded-[1.75rem] px-4 pt-4 pb-5">
        <Gauge value={Math.max(0, saving)}>
          <span className="text-5xl font-light tabular-nums">
            {saving > 0 ? `−${Math.round(saving * 100)}` : '0'}
            <span className="align-top text-2xl">%</span>
          </span>
          <span className="text-sm text-muted">smaller</span>
        </Gauge>
        <div className="-mt-4 flex items-center justify-center gap-3 text-lg">
          <span className="font-light text-muted">{formatBytes(originalSize)}</span>
          <Icon name="back" className="h-5 w-5 rotate-180 text-faint" />
          <span className="font-medium text-accent">{formatBytes(result.size)}</span>
        </div>
      </div>

      {native ? (
        <div className="grid grid-cols-2 gap-3">
          <PillButton accent onClick={() => save(false)} disabled={busy || saved}>
            <Icon name="download" />
            {busy ? 'Saving…' : saved ? 'Saved' : 'Save'}
          </PillButton>
          <PillButton onClick={() => save(true)} disabled={busy}>
            <Icon name="share" />
            Share
          </PillButton>
        </div>
      ) : (
        <a
          href={result.url}
          download={fileName}
          className="flex h-16 items-center justify-center gap-2.5 rounded-full bg-accent text-lg font-medium text-ink"
        >
          <Icon name="download" />
          Download
        </a>
      )}
    </div>
  )
}
