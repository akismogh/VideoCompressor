import { FFmpeg } from '@ffmpeg/ffmpeg'
import { toBlobURL } from '@ffmpeg/util'

const CORE_VERSION = '0.12.6'
const BASE_URL = `https://unpkg.com/@ffmpeg/core@${CORE_VERSION}/dist/esm`

let instance: FFmpeg | null = null
let loadPromise: Promise<FFmpeg> | null = null

export function getFFmpeg(): FFmpeg {
  if (!instance) {
    instance = new FFmpeg()
  }
  return instance
}

export async function loadFFmpeg(): Promise<FFmpeg> {
  const ffmpeg = getFFmpeg()

  if (ffmpeg.loaded) {
    return ffmpeg
  }

  if (!loadPromise) {
    loadPromise = (async () => {
      const [coreURL, wasmURL] = await Promise.all([
        toBlobURL(`${BASE_URL}/ffmpeg-core.js`, 'text/javascript'),
        toBlobURL(`${BASE_URL}/ffmpeg-core.wasm`, 'application/wasm'),
      ])
      await ffmpeg.load({ coreURL, wasmURL })
      return ffmpeg
    })()
  }

  return loadPromise
}

export function onFFmpegProgress(
  ffmpeg: FFmpeg,
  callback: (ratio: number) => void,
): () => void {
  const handler = ({ progress }: { progress: number }) => {
    if (Number.isFinite(progress)) {
      callback(Math.min(1, Math.max(0, progress)))
    }
  }
  ffmpeg.on('progress', handler)
  return () => ffmpeg.off('progress', handler)
}
