// Bridge to the Android app shell (android/), which injects `window.VCAndroid`.
// In a normal browser it is absent and every helper here is a no-op.

export type RecordingPhase = 'idle' | 'starting' | 'recording' | 'finishing'

export interface RecordingState {
  phase: RecordingPhase
  startedAt: number
  /** A consented capture session is alive: recording again won't prompt. */
  sessionActive: boolean
  /** When the idle session ends (ms epoch); 0 while recording or with no session. */
  sessionEndsAt: number
}

interface VCAndroidBridge {
  getRecordingState(): string
  takePendingMedia(): string
  takeError(): string
  startRecording(deviceAudio: boolean, mic: boolean): void
  stopRecording(): void
  endSession(): void
  recordCamera(): void
  beginOutput(name: string): void
  appendOutput(base64: string): void
  finishOutput(share: boolean): boolean
  shareSaved(): boolean
}

declare global {
  interface Window {
    VCAndroid?: VCAndroidBridge
  }
}

export const native: VCAndroidBridge | null = window.VCAndroid ?? null

const IDLE: RecordingState = {
  phase: 'idle',
  startedAt: 0,
  sessionActive: false,
  sessionEndsAt: 0,
}
const CHUNK_BYTES = 512 * 1024

export function readRecordingState(): RecordingState {
  if (!native) return IDLE
  try {
    return JSON.parse(native.getRecordingState()) as RecordingState
  } catch {
    return IDLE
  }
}

/** Fires when the app shell reports a change, or the app comes back to the foreground. */
export function onNativeChange(callback: () => void): () => void {
  window.addEventListener('vc-native', callback)
  document.addEventListener('visibilitychange', callback)
  return () => {
    window.removeEventListener('vc-native', callback)
    document.removeEventListener('visibilitychange', callback)
  }
}

export interface NativeMedia {
  url: string
  name: string
  mime: string
}

/** A recording / shared video the app shell has waiting for us, if any. */
export function takePendingNativeMedia(): NativeMedia | null {
  const raw = native?.takePendingMedia()
  return raw ? (JSON.parse(raw) as NativeMedia) : null
}

export async function fetchNativeMedia({ url, name, mime }: NativeMedia): Promise<File> {
  const blob = await (await fetch(url)).blob()
  return new File([blob], name, { type: mime || blob.type || 'video/mp4' })
}

/** Streams the result to the app shell, which saves it to the gallery (and shares it). */
export async function saveOutputNative(
  blob: Blob,
  name: string,
  share: boolean,
): Promise<boolean> {
  if (!native) return false
  native.beginOutput(name)
  for (let offset = 0; offset < blob.size; offset += CHUNK_BYTES) {
    const bytes = new Uint8Array(
      await blob.slice(offset, offset + CHUNK_BYTES).arrayBuffer(),
    )
    native.appendOutput(toBase64(bytes))
  }
  return native.finishOutput(share)
}

function toBase64(bytes: Uint8Array): string {
  let binary = ''
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000))
  }
  return btoa(binary)
}
