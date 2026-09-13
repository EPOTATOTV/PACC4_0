/* PACC 管控平台 — 离线缓存 Service Worker */
/* 策略：只缓存静态资源（Shell），忽略所有 /api 与 /ws 动态请求，保证不缓存敏感数据。 */
const CACHE = 'pacc-shell-v1'
const PRECACHE = ['/', '/index.html', '/manifest.webmanifest', '/favicon.ico']

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => cache.addAll(PRECACHE).catch(() => undefined))
      .then(() => self.skipWaiting()),
  )
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  )
})

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url)
  // 绝不缓存动态接口 / WebSocket，避免把账号、检测等敏感数据留在本地
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/ws/')) return

  // 导航请求：网络优先，失败回退缓存（离线可用）
  if (event.request.mode === 'navigate') {
    event.respondWith(
      fetch(event.request).catch(() => caches.match('/index.html')),
    )
    return
  }

  // 静态资源：缓存优先，后备网络并写入缓存
  event.respondWith(
    caches.match(event.request).then(
      (hit) =>
        hit ||
        fetch(event.request).then((res) => {
          if (res.ok && url.origin === self.location.origin) {
            const copy = res.clone()
            caches.open(CACHE).then((c) => c.put(event.request, copy))
          }
          return res
        }),
    ),
  )
})