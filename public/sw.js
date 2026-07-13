const SHARE_CACHE = 'share-target-cache'
const SHARE_KEY = '/__shared-video__'

self.addEventListener('install', () => {
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim())
})

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url)
  if (event.request.method === 'POST' && url.pathname.endsWith('/share-target/')) {
    event.respondWith(handleShareTarget(event.request))
  }
})

async function handleShareTarget(request) {
  try {
    const formData = await request.formData()
    const file = formData.get('video')
    if (file) {
      const cache = await caches.open(SHARE_CACHE)
      await cache.put(
        SHARE_KEY,
        new Response(file, {
          headers: {
            'Content-Type': file.type || 'video/mp4',
            'X-File-Name': encodeURIComponent(file.name || 'shared-video.mp4'),
          },
        }),
      )
    }
  } catch {
    // No file, or the browser sent something we couldn't parse — the app
    // will just fall back to showing the normal upload screen.
  }
  return Response.redirect(new URL('?shared=1', self.registration.scope).href, 303)
}
