import { useEffect, useRef, useState } from 'react'
import { FileUpload } from './components/FileUpload'
import { VideoTrimmer } from './components/VideoTrimmer'
import { CompressionPanel } from './components/CompressionPanel'
import { ResultPanel } from './components/ResultPanel'
import { RecordPanel } from './components/RecordPanel'
import { useFFmpeg } from './hooks/useFFmpeg'
import { useNativeRecording } from './hooks/useNativeRecording'
import { readVideoMeta } from './lib/videoMeta'
import { compressVideo } from './lib/compress'
import { onFFmpegProgress } from './lib/ffmpeg'
import { consumePendingSharedFile } from './lib/shareTarget'
import type { CompressResult, VideoMeta } from './types'

function App() {
  const [video, setVideo] = useState<VideoMeta | null>(null)
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [awaitingShare, setAwaitingShare] = useState(
    () => new URLSearchParams(window.location.search).has('shared'),
  )
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
      file.type.startsWith('video/') || /\.(mp4|mov)$/i.test(file.name)
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

  const recorder = useNativeRecording(handleSelectFile)
  const loadingInput = awaitingShare || recorder.importing

  useEffect(() => {
    if (!awaitingShare) return
    window.history.replaceState({}, '', window.location.pathname)
    consumePendingSharedFile()
      .then((file) => {
        if (file) return handleSelectFile(file)
        setUploadError('No shared video was found. Try sharing it again.')
      })
      .finally(() => setAwaitingShare(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

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
        {recorder.supported &&
          !loadingInput &&
          (!video || recorder.state.phase !== 'idle') && (
            <RecordPanel
              state={recorder.state}
              error={recorder.error}
              onStart={recorder.start}
              onStop={recorder.stop}
              onEndSession={recorder.endSession}
            />
          )}

        {loadingInput && !video && (
          <div className="flex flex-col items-center gap-3 rounded-2xl border-2 border-dashed border-slate-300 dark:border-slate-700 px-6 py-16 text-center">
            <div className="text-4xl animate-pulse">🎬</div>
            <p className="text-base font-medium text-slate-700 dark:text-slate-200">
              {recorder.importing ? 'Loading video…' : 'Loading shared video…'}
            </p>
          </div>
        )}

        {!loadingInput && !video && (
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
                key={result.url}
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
