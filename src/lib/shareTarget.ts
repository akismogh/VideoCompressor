const SHARE_CACHE = 'share-target-cache'
const SHARE_KEY = '/__shared-video__'

export function registerServiceWorker() {
  // The Android app shell has its own share handling and no SW origin to serve from.
  if (!('serviceWorker' in navigator) || window.VCAndroid) return
  window.addEventListener('load', () => {
    navigator.serviceWorker
      .register(new URL('sw.js', document.baseURI))
      .catch(() => {})
  })
}

export async function consumePendingSharedFile(): Promise<File | null> {
  if (!('caches' in window)) return null

  const cache = await caches.open(SHARE_CACHE)
  const response = await cache.match(SHARE_KEY)
  if (!response) return null

  await cache.delete(SHARE_KEY)

  const blob = await response.blob()
  const name = decodeURIComponent(
    response.headers.get('X-File-Name') || 'shared-video.mp4',
  )
  const type = response.headers.get('Content-Type') || blob.type || 'video/mp4'
  return new File([blob], name, { type })
}
