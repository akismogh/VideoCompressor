import { useEffect, useRef, useState } from 'react'
import { HomeScreen } from './components/HomeScreen'
import { EditScreen } from './components/EditScreen'
import { ResultScreen } from './components/ResultScreen'
import { useFFmpeg } from './hooks/useFFmpeg'
import { useNativeRecording } from './hooks/useNativeRecording'
import { readVideoMeta } from './lib/videoMeta'
import { compressVideo } from './lib/compress'
import { onFFmpegProgress } from './lib/ffmpeg'
import { consumePendingSharedFile } from './lib/shareTarget'
import type { CompressResult, VideoMeta } from './types'

declare global {
  interface Window {
    /** Called by the Android shell's back button; true when the page handled it. */
    __vcBack?: () => boolean
  }
}

function App() {
  const [video, setVideo] = useState<VideoMeta | null>(null)
  const [inputError, setInputError] = useState<string | null>(null)
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
  const [showResult, setShowResult] = useState(false)

  const { ensureLoaded } = useFFmpeg()
  const resultUrlRef = useRef<string | null>(null)
  const videoUrlRef = useRef<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const screen = !video ? 'home' : showResult && result ? 'result' : 'edit'

  useEffect(() => {
    return () => {
      if (resultUrlRef.current) URL.revokeObjectURL(resultUrlRef.current)
      if (videoUrlRef.current) URL.revokeObjectURL(videoUrlRef.current)
    }
  }, [])

  useEffect(() => {
    window.scrollTo(0, 0)
  }, [screen])

  const clearResult = () => {
    if (resultUrlRef.current) URL.revokeObjectURL(resultUrlRef.current)
    resultUrlRef.current = null
    setResult(null)
    setShowResult(false)
  }

  const handleSelectFile = async (file: File) => {
    setInputError(null)
    if (!file.type.startsWith('video/') && !/\.(mp4|mov)$/i.test(file.name)) {
      setInputError('Please choose a video file.')
      return
    }
    try {
      const meta = await readVideoMeta(file)
      if (videoUrlRef.current) URL.revokeObjectURL(videoUrlRef.current)
      videoUrlRef.current = meta.url
      clearResult()
      setVideo(meta)
      setTrimStart(0)
      setTrimEnd(meta.duration)
      setProcessError(null)
    } catch (err) {
      setInputError(err instanceof Error ? err.message : 'Could not read that video.')
    }
  }

  const recorder = useNativeRecording(handleSelectFile)

  // Browser fallback (PWA share target); the Android shell delivers files itself.
  useEffect(() => {
    if (!awaitingShare) return
    window.history.replaceState({}, '', window.location.pathname)
    consumePendingSharedFile()
      .then((file) => {
        if (file) return handleSelectFile(file)
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
      const output = await compressVideo(ffmpeg, video, { trimStart, trimEnd, mute })
      if (resultUrlRef.current) URL.revokeObjectURL(resultUrlRef.current)
      resultUrlRef.current = output.url
      setResult(output)
      setShowResult(true)
    } catch (err) {
      setProcessError(err instanceof Error ? err.message : 'Compression failed. Try again.')
    } finally {
      unsubscribe?.()
      setProcessing(false)
    }
  }

  const goHome = () => {
    clearResult()
    if (videoUrlRef.current) URL.revokeObjectURL(videoUrlRef.current)
    videoUrlRef.current = null
    setVideo(null)
    setProcessError(null)
    setInputError(null)
    setProgress(0)
  }

  const pickFromLibrary = () => {
    if (recorder.supported) recorder.pickVideo()
    else fileInputRef.current?.click()
  }

  // Android back: result → edit → home → (shell closes the app).
  useEffect(() => {
    window.__vcBack = () => {
      if (processing) return true
      if (screen === 'result') {
        setShowResult(false)
        return true
      }
      if (screen === 'edit') {
        goHome()
        return true
      }
      return false
    }
  })

  return (
    <div
      className="mx-auto min-h-screen max-w-lg px-5 pb-10"
      style={{
        paddingTop: 'calc(var(--sat) + 1rem)',
        paddingBottom: 'calc(var(--sab) + 2rem)',
      }}
    >
      {/* Scrim under the status bar so scrolled content doesn't collide with the clock. */}
      <div
        className="pointer-events-none fixed inset-x-0 top-0 z-20 bg-gradient-to-b from-[#5f5f5d] from-70% to-transparent"
        style={{ height: 'calc(var(--sat) + 0.75rem)' }}
      />

      {screen === 'home' && (
        <HomeScreen
          state={recorder.state}
          loading={recorder.importing || awaitingShare}
          error={recorder.error ?? inputError}
          onRecordScreen={recorder.start}
          onStopRecording={recorder.stop}
          onCamera={recorder.camera}
          onLibrary={pickFromLibrary}
          onEndSession={recorder.endSession}
        />
      )}

      {screen === 'edit' && video && (
        <EditScreen
          video={video}
          trimStart={trimStart}
          trimEnd={trimEnd}
          onTrimChange={(start, end) => {
            setTrimStart(start)
            setTrimEnd(end)
          }}
          mute={mute}
          onMuteChange={setMute}
          onCompress={handleCompress}
          processing={processing}
          busyLabel={busyLabel}
          progress={progress}
          error={processError}
          onBack={goHome}
        />
      )}

      {screen === 'result' && video && result && (
        <ResultScreen
          key={result.url}
          result={result}
          originalSize={video.file.size}
          originalName={video.file.name}
          onBack={() => setShowResult(false)}
          onNew={goHome}
        />
      )}

      <input
        ref={fileInputRef}
        type="file"
        accept="video/*"
        className="hidden"
        onChange={(e) => {
          const file = e.target.files?.[0]
          if (file) void handleSelectFile(file)
          e.target.value = ''
        }}
      />
    </div>
  )
}

export default App
