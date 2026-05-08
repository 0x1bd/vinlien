import {browser} from '$app/environment';
import {writable} from 'svelte/store';
import {apiRequest} from '$lib/utils/api';
import {queue, similarTracks} from '$lib/utils/store';
import type {Track} from '$lib/utils/types';

const SESSION_KEY = 'vl_artwork';
const MAX_CONCURRENT = 4;

export function proxyArtwork(url: string | null | undefined): string | undefined {
    if (!url) return undefined;
    return `/api/artwork?url=${encodeURIComponent(url)}`;
}

export function isLowQualityArtwork(url: string | null | undefined): boolean {
    return !url || url.includes('ytimg.com');
}

function loadSessionCache(): Record<string, string> {
    if (!browser) return {};
    try {
        return JSON.parse(sessionStorage.getItem(SESSION_KEY) ?? '{}');
    } catch {
        return {};
    }
}

function saveSessionCache(cache: Record<string, string>) {
    if (!browser) return;
    try {
        sessionStorage.setItem(SESSION_KEY, JSON.stringify(cache));
    } catch {}
}

const initialCache = loadSessionCache();

export const enrichedArtworkByTrackId = writable<Record<string, string>>(initialCache);

const attemptedKeys = new Set<string>(Object.keys(initialCache));
const pendingKeys = new Set<string>();

let active = 0;
const jobQueue: Array<() => Promise<void>> = [];

function artworkKeys(track: Track): string[] {
    const keys = [
        track.id,
        track.canonicalId,
        `${track.artist.toLowerCase().trim()}:::${track.title.toLowerCase().trim()}`
    ];
    return [...new Set(keys.filter((key): key is string => !!key && key.trim().length > 0))];
}

function cachedArtwork(track: Track, cache: Record<string, string>): string | null {
    for (const key of artworkKeys(track)) {
        const url = cache[key];
        if (url) return url;
    }
    return null;
}

export function trackArtworkUrl(track: Track | null | undefined, cache: Record<string, string>): string | null {
    if (!track) return null;
    return cachedArtwork(track, cache) || track.artworkUrl || null;
}

function updateTrackArtwork(track: Track, artworkUrl: string): Track {
    return artworkKeys(track).some(key => latestCache[key] === artworkUrl) ? {...track, artworkUrl} : track;
}

let latestCache = initialCache;
enrichedArtworkByTrackId.subscribe(cache => {
    latestCache = cache;
});

function drainQueue() {
    while (active < MAX_CONCURRENT && jobQueue.length > 0) {
        active++;
        jobQueue.shift()!().finally(() => {
            active--;
            drainQueue();
        });
    }
}

function enqueue(job: () => Promise<void>) {
    jobQueue.push(job);
    drainQueue();
}

function applyResult(track: Track, artworkUrl: string) {
    const keys = artworkKeys(track);
    keys.forEach(key => attemptedKeys.add(key));
    enrichedArtworkByTrackId.update(cache => {
        const next = {...cache};
        keys.forEach(key => {
            next[key] = artworkUrl;
        });
        saveSessionCache(next);
        return next;
    });

    queue.update(items => items.map(item => updateTrackArtwork(item, artworkUrl)));
    similarTracks.update(items => items.map(item => updateTrackArtwork(item, artworkUrl)));
}

function markAttempted(track: Track) {
    artworkKeys(track).forEach(key => attemptedKeys.add(key));
}

function isPending(track: Track): boolean {
    return artworkKeys(track).some(key => pendingKeys.has(key));
}

function hasAttempted(track: Track): boolean {
    return artworkKeys(track).some(key => attemptedKeys.has(key));
}

function setPending(track: Track, pending: boolean) {
    artworkKeys(track).forEach(key => {
        if (pending) pendingKeys.add(key);
        else pendingKeys.delete(key);
    });
}

function needsEnrich(track: Track): boolean {
    return browser &&
        !cachedArtwork(track, latestCache) &&
        !hasAttempted(track) &&
        !isPending(track) &&
        isLowQualityArtwork(track.artworkUrl);
}

export function enrichArtwork(track: Track): Promise<void> {
    if (!needsEnrich(track)) return Promise.resolve();
    setPending(track, true);

    return new Promise<void>((resolve) => {
        enqueue(async () => {
            try {
                const result = await apiRequest('/api/artwork/enrich', {
                    method: 'POST',
                    body: {id: track.id, title: track.title, artist: track.artist}
                });
                if (result?.artworkUrl) {
                    applyResult(track, result.artworkUrl);
                } else {
                    markAttempted(track);
                }
            } catch {
                markAttempted(track);
            } finally {
                setPending(track, false);
                resolve();
            }
        });
    });
}

export function batchEnrichArtwork(tracks: Track[]): void {
    const needed = tracks.filter(needsEnrich);
    if (needed.length === 0) return;

    needed.forEach(track => setPending(track, true));

    for (let i = 0; i < needed.length; i += 10) {
        const chunk = needed.slice(i, i + 10);
        enqueue(async () => {
            try {
                const result = await apiRequest('/api/artwork/enrich/batch', {
                    method: 'POST',
                    body: {tracks: chunk.map(t => ({id: t.id, title: t.title, artist: t.artist}))}
                });
                const results = (result?.results ?? {}) as Record<string, string | null>;
                chunk.forEach(track => {
                    const url = results[track.id];
                    if (url) applyResult(track, url);
                    else markAttempted(track);
                });
            } catch {
                chunk.forEach(markAttempted);
            } finally {
                chunk.forEach(track => setPending(track, false));
            }
        });
    }
}

export function placeholderGradient(seed: string): string {
    let h = 0;
    for (let i = 0; i < seed.length; i++) h = (Math.imul(31, h) + seed.charCodeAt(i)) | 0;
    const hue1 = (h >>> 0) % 360;
    const hue2 = (hue1 + 40 + ((h >>> 8) % 80)) % 360;
    const angle = (h >>> 16) % 360;
    return `linear-gradient(${angle}deg, hsl(${hue1},60%,30%), hsl(${hue2},70%,50%))`;
}
