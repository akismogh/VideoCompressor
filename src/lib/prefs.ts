// Screen-recording audio choices, remembered on this device.

export interface AudioPrefs {
  device: boolean
  mic: boolean
}

const KEY = 'videoCompressor.recordAudio'

export function loadAudioPrefs(): AudioPrefs {
  try {
    const saved = JSON.parse(localStorage.getItem(KEY) ?? 'null')
    if (saved && typeof saved.device === 'boolean' && typeof saved.mic === 'boolean') {
      return saved
    }
  } catch {
    // ignore unreadable storage
  }
  return { device: true, mic: false }
}

export function saveAudioPrefs(prefs: AudioPrefs) {
  try {
    localStorage.setItem(KEY, JSON.stringify(prefs))
  } catch {
    // per-device convenience only
  }
}
