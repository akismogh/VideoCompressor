import { useCallback, useEffect, useRef, useState } from 'react'
import {
  fetchNativeMedia,
  native,
  onNativeChange,
  readRecordingState,
  takePendingNativeMedia,
  type RecordingState,
} from '../lib/native'

/**
 * Screen-recording state from the Android app shell. Finished recordings (and
 * videos shared into the app) are delivered through `onFile`.
 */
export function useNativeRecording(onFile: (file: File) => Promise<void>) {
  const [state, setState] = useState<RecordingState>(readRecordingState)
  const [error, setError] = useState<string | null>(null)
  const [importing, setImporting] = useState(false)
  const onFileRef = useRef(onFile)

  useEffect(() => {
    onFileRef.current = onFile
  })

  useEffect(() => {
    if (!native) return
    const bridge = native

    const sync = async () => {
      setState(readRecordingState())
      const err = bridge.takeError()
      if (err) setError(err)

      const media = takePendingNativeMedia()
      if (!media) return
      setImporting(true)
      try {
        await onFileRef.current(await fetchNativeMedia(media))
      } catch {
        setError('Could not load the recording.')
      } finally {
        setImporting(false)
      }
    }

    void sync()
    return onNativeChange(() => void sync())
  }, [])

  const start = useCallback((deviceAudio: boolean, mic: boolean) => {
    setError(null)
    native?.startRecording(deviceAudio, mic)
  }, [])

  const stop = useCallback(() => native?.stopRecording(), [])
  const endSession = useCallback(() => native?.endSession(), [])

  return {
    supported: native !== null,
    state,
    error,
    importing,
    start,
    stop,
    endSession,
  }
}
