/// <reference types="@sveltejs/kit" />
/// <reference no-default-lib="true"/>
/// <reference lib="esnext" />
/// <reference lib="webworker" />

import { build, files, version } from '$service-worker';

declare const self: ServiceWorkerGlobalScope;

const SHELL_CACHE = `shell-${version}`;

self.addEventListener('install', (event: ExtendableEvent) => {
    event.waitUntil(
        caches.open(SHELL_CACHE).then(async cache => {
            for (const asset of [...build, ...files]) {
                await cache.add(asset).catch(() => {});
            }
            await cache.add('/').catch(() => {});
            self.skipWaiting();
        })
    );
});

self.addEventListener('activate', (event: ExtendableEvent) => {
    event.waitUntil(
        caches.keys()
            .then(keys => Promise.all(
                keys.filter(k => k.startsWith('shell-') && k !== SHELL_CACHE)
                    .map(k => caches.delete(k))
            ))
            .then(() => self.clients.claim())
    );
});

self.addEventListener('fetch', (event: FetchEvent) => {
    const url = new URL(event.request.url);
    if (url.origin !== self.location.origin) return;

    if (url.pathname.startsWith('/_app/immutable/')) {
        event.respondWith(
            caches.match(event.request).then(r => r ?? fetch(event.request))
        );
        return;
    }

    if (event.request.mode === 'navigate') {
        event.respondWith(
            fetch(event.request).catch(async () => {
                const cache = await caches.open(SHELL_CACHE);
                return (await cache.match('/')) ?? new Response('Offline', {status: 503});
            })
        );
        return;
    }

    if (!url.pathname.startsWith('/api/')) {
        event.respondWith(
            fetch(event.request).catch(() =>
                caches.match(event.request).then(r => r ?? new Response('', {status: 404}))
            )
        );
    }
});
