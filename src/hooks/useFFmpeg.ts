import { useCallback, useEffect, useRef, useState } from 'react'
import { loadFFmpeg } from '../lib/ffmpeg'

interface UseFFmpegState {
  loaded: boolean
  loading: boolean
  error: string | null
}

export function useFFmpeg() {
  const [state, setState] = useState<UseFFmpegState>({
    loaded: false,
    loading: false,
    error: null,
  })
  const mounted = useRef(true)

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  const ensureLoaded = useCallback(async () => {
    setState((s) => (s.loaded ? s : { ...s, loading: true, error: null }))
    try {
      const ffmpeg = await loadFFmpeg()
      if (mounted.current) {
        setState({ loaded: true, loading: false, error: null })
      }
      return ffmpeg
    } catch (err) {
      if (mounted.current) {
        setState({
          loaded: false,
          loading: false,
          error: err instanceof Error ? err.message : 'Failed to load FFmpeg',
        })
      }
      throw err
    }
  }, [])

  return { ...state, ensureLoaded }
}
