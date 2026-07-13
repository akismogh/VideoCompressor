import { useCallback, useRef, useState } from 'react'

interface FileUploadProps {
  onSelect: (file: File) => void
  error: string | null
}

export function FileUpload({ onSelect, error }: FileUploadProps) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [dragging, setDragging] = useState(false)

  const handleFiles = useCallback(
    (files: FileList | null) => {
      const file = files?.[0]
      if (!file) return
      onSelect(file)
    },
    [onSelect],
  )

  return (
    <div className="w-full">
      <label
        onDragOver={(e) => {
          e.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(e) => {
          e.preventDefault()
          setDragging(false)
          handleFiles(e.dataTransfer.files)
        }}
        className={`flex flex-col items-center justify-center gap-3 rounded-2xl border-2 border-dashed px-6 py-16 text-center transition-colors cursor-pointer ${
          dragging
            ? 'border-indigo-400 bg-indigo-500/10'
            : 'border-slate-300 dark:border-slate-700 hover:border-indigo-400 hover:bg-indigo-500/5'
        }`}
      >
        <div className="text-4xl">🎬</div>
        <p className="text-base font-medium text-slate-700 dark:text-slate-200">
          Tap to choose a video, or drag one here
        </p>
        <p className="text-sm text-slate-400 dark:text-slate-500">
          MP4 or MOV · processed entirely on your device
        </p>
        <input
          ref={inputRef}
          type="file"
          accept="video/mp4,video/quicktime,.mp4,.mov"
          className="hidden"
          onChange={(e) => handleFiles(e.target.files)}
        />
      </label>
      {error && (
        <p className="mt-3 text-sm text-red-500 text-center">{error}</p>
      )}
    </div>
  )
}
