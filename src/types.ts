export interface VideoMeta {
  file: File
  url: string
  duration: number
  width: number
  height: number
}

export interface CompressOptions {
  trimStart: number
  trimEnd: number
  mute: boolean
}

export interface CompressResult {
  url: string
  blob: Blob
  size: number
}
