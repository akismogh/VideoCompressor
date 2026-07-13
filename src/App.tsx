import { useEffect, useRef, useState } from 'react'
import { FileUpload } from './components/FileUpload'
import { VideoTrimmer } from './components/VideoTrimmer'
import { CompressionPanel } from './components/CompressionPanel'
import { ResultPanel } from './components/ResultPanel'
import { useFFmpeg } from './hooks/useFFmpeg'
import { readVideoMeta } from './lib/videoMeta'
import { compressVideo } from './lib/compress'
import { onFFmpegProgress } from './lib/ffmpeg'
import type { CompressResult, VideoMeta } from './types'

const ACCEPTED_TYPES = ['video/mp4', 'video/quicktime']

function App() {
  const [video, setVideo] = useState<VideoMeta | null>(null)
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [trimStart, setTrimStart] = useState(0)
  const [trimEnd, setTrimEnd] = useState(0)
  const [mute, setMute] = useState(false)

  const [processing, setProcessing] = useState(false)
  const [busyLabel, setBusyLabel] = useState('Compressing…')
  const [progress, setProgress] = useState(0)
  const [processError, setProcessError] = useState<string | null>(null)
  const [result, setResult] = useState<CompressResult | null>(null)

  const { ensureLoaded } = useFFmpeg()
  const resultUrlRef = useRef<string | null>(null)
  const videoUrlRef = useRef<string | null>(null)

  useEffect(() => {
    return () => {
      if (resultUrlRef.current) URL.revokeObjectURL(resultUrlRef.current)
      if (videoUrlRef.current) URL.revokeObjectURL(videoUrlRef.current)
    }
  }, [])

  const handleSelectFile = async (file: File) => {
    setUploadError(null)

    const looksValid =
      ACCEPTED_TYPES.includes(file.type) || /\.(mp4|mov)$/i.test(file.name)
    if (!looksValid) {
      setUploadError('Please choose an MP4 or MOV video.')
      return
    }

    try {
      const meta = await readVideoMeta(file)
      if (videoUrlRef.current) URL.revokeObjectURL(videoUrlRef.current)
      videoUrlRef.current = meta.url

      if (resultUrlRef.current) {
        URL.revokeObjectURL(resultUrlRef.current)
        resultUrlRef.current = null
      }

      setVideo(meta)
      setTrimStart(0)
      setTrimEnd(meta.duration)
      setResult(null)
      setProcessError(null)
    } catch (err) {
      setUploadError(
        err instanceof Error ? err.message : 'Could not read that video.',
      )
    }
  }

  const handleCompress = async () => {
    if (!video) return
    setProcessing(true)
    setProcessError(null)
    setProgress(0)
    setBusyLabel('Loading engine…')

    let unsubscribe: (() => void) | null = null

    try {
      const ffmpeg = await ensureLoaded()
      setBusyLabel('Compressing…')
      unsubscribe = onFFmpegProgress(ffmpeg, setProgress)

      const output = await compressVideo(ffmpeg, video, {
        trimStart,
        trimEnd,
        mute,
      })

      if (resultUrlRef.current) URL.revokeObjectURL(resultUrlRef.current)
      resultUrlRef.current = output.url
      setResult(output)
    } catch (err) {
      setProcessError(
        err instanceof Error ? err.message : 'Compression failed. Try again.',
      )
    } finally {
      unsubscribe?.()
      setProcessing(false)
    }
  }

  const handleReset = () => {
    if (resultUrlRef.current) URL.revokeObjectURL(resultUrlRef.current)
    if (videoUrlRef.current) URL.revokeObjectURL(videoUrlRef.current)
    resultUrlRef.current = null
    videoUrlRef.current = null
    setVideo(null)
    setResult(null)
    setProcessError(null)
    setUploadError(null)
    setProgress(0)
  }

  return (
    <div className="min-h-screen bg-slate-50 dark:bg-slate-950 text-slate-900 dark:text-slate-100">
      <header className="mx-auto max-w-lg px-4 pt-8 pb-4 text-center">
        <h1 className="text-2xl font-semibold">Video Compressor</h1>
        <p className="mt-1 text-sm text-slate-400 dark:text-slate-500">
          Trim and shrink videos for sharing — all on your device, nothing
          uploaded.
        </p>
      </header>

      <main className="mx-auto flex max-w-lg flex-col gap-5 px-4 pb-16">
        {!video && (
          <FileUpload onSelect={handleSelectFile} error={uploadError} />
        )}

        {video && (
          <>
            <VideoTrimmer
              video={video}
              trimStart={trimStart}
              trimEnd={trimEnd}
              onChange={(start, end) => {
                setTrimStart(start)
                setTrimEnd(end)
              }}
            />

            <CompressionPanel
              mute={mute}
              onMuteChange={setMute}
              onCompress={handleCompress}
              busy={processing}
              busyLabel={busyLabel}
              progress={progress}
            />

            {processError && (
              <p className="text-sm text-red-500 text-center">
                {processError}
              </p>
            )}

            {result && (
              <ResultPanel
                result={result}
                originalSize={video.file.size}
                originalName={video.file.name}
              />
            )}

            <button
              type="button"
              onClick={handleReset}
              className="text-sm text-slate-400 hover:text-slate-600 dark:hover:text-slate-300"
            >
              Choose a different video
            </button>
          </>
        )}
      </main>
    </div>
  )
}

export default App
