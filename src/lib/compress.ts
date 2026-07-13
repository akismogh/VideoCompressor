import type { FFmpeg } from '@ffmpeg/ffmpeg'
import { fetchFile } from '@ffmpeg/util'
import type { CompressOptions, CompressResult, VideoMeta } from '../types'

const INPUT_NAME = 'input.mp4'
const OUTPUT_NAME = 'output.mp4'

function buildArgs(video: VideoMeta, opts: CompressOptions): string[] {
  const isPortrait = video.height > video.width
  const scaleFilter = isPortrait
    ? "scale='min(720,iw)':-2"
    : "scale=-2:'min(720,ih)'"

  const args = [
    '-ss',
    opts.trimStart.toFixed(2),
    '-to',
    opts.trimEnd.toFixed(2),
    '-i',
    INPUT_NAME,
    '-vf',
    scaleFilter,
    '-r',
    '30',
    '-c:v',
    'libx264',
    '-preset',
    'veryfast',
    '-b:v',
    '1500k',
    '-maxrate',
    '1500k',
    '-bufsize',
    '3000k',
  ]

  if (opts.mute) {
    args.push('-an')
  } else {
    args.push('-c:a', 'aac', '-b:a', '128k')
  }

  args.push('-movflags', '+faststart', '-y', OUTPUT_NAME)
  return args
}

export async function compressVideo(
  ffmpeg: FFmpeg,
  video: VideoMeta,
  opts: CompressOptions,
): Promise<CompressResult> {
  await ffmpeg.writeFile(INPUT_NAME, await fetchFile(video.file))

  try {
    await ffmpeg.exec(buildArgs(video, opts))

    const data = await ffmpeg.readFile(OUTPUT_NAME)
    const bytes =
      typeof data === 'string' ? new TextEncoder().encode(data) : data
    const buffer = bytes.buffer.slice(
      bytes.byteOffset,
      bytes.byteOffset + bytes.byteLength,
    ) as ArrayBuffer
    const blob = new Blob([buffer], { type: 'video/mp4' })

    return {
      url: URL.createObjectURL(blob),
      blob,
      size: blob.size,
    }
  } finally {
    await ffmpeg.deleteFile(INPUT_NAME).catch(() => {})
    await ffmpeg.deleteFile(OUTPUT_NAME).catch(() => {})
  }
}
