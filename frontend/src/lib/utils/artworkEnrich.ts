import {writable} from 'svelte/store';
import {queue} from '$lib/utils/store';
import {apiRequest} from '$lib/utils/api';
import type {Track} from '$lib/utils/types';

const SESSION_KEY = 'vl_artwork';
const MAX_CONCURRENT = 4;

function loadSessionCache(): Record<string, string> {
    if (typeof sessionStorage === 'undefined') return {};
    try { return JSON.parse(sessionStorage.getItem(SESSION_KEY) ?? '{}'); } catch { return {}; }
}

function saveSessionCache(cache: Record<string, string>) {
    if (typeof sessionStorage === 'undefined') return;
    try { sessionStorage.setItem(SESSION_KEY, JSON.stringify(cache)); } catch {}
}

export const enrichedArtworkByTrackId = writable<Record<string, string>>(loadSessionCache());

const resolvedIds = new Set<string>(Object.keys(loadSessionCache()));
const pendingIds = new Set<string>();

let active = 0;
const jobQueue: Array<() => Promise<void>> = [];

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

function applyResult(id: string, artworkUrl: string) {
    resolvedIds.add(id);
    enrichedArtworkByTrackId.update(m => {
        const next = {...m, [id]: artworkUrl};
        saveSessionCache(next);
        return next;
    });
    queue.update(q => q.map(t => t.id === id ? {...t, artworkUrl} : t));
}

function needsEnrich(track: Track): boolean {
    return !resolvedIds.has(track.id) &&
        !pendingIds.has(track.id) &&
        (!track.artworkUrl || track.artworkUrl.includes('ytimg.com'));
}

export function enrichArtwork(track: Track): Promise<void> {
    if (!needsEnrich(track)) return Promise.resolve();
    pendingIds.add(track.id);

    return new Promise<void>((resolve) => {
        enqueue(async () => {
            try {
                const result = await apiRequest('/api/artwork/enrich', {
                    method: 'POST',
                    body: {id: track.id, title: track.title, artist: track.artist}
                });
                if (result?.artworkUrl) applyResult(track.id, result.artworkUrl);
            } catch { /* silent */ } finally {
                pendingIds.delete(track.id);
                resolve();
            }
        });
    });
}

export function batchEnrichArtwork(tracks: Track[]): void {
    const needed = tracks.filter(needsEnrich);
    if (needed.length === 0) return;

    needed.forEach(t => pendingIds.add(t.id));

    for (let i = 0; i < needed.length; i += 10) {
        const chunk = needed.slice(i, i + 10);
        enqueue(async () => {
            try {
                const result = await apiRequest('/api/artwork/enrich/batch', {
                    method: 'POST',
                    body: {tracks: chunk.map(t => ({id: t.id, title: t.title, artist: t.artist}))}
                });
                if (result?.results) {
                    enrichedArtworkByTrackId.update(m => {
                        const next = {...m};
                        for (const [id, url] of Object.entries(result.results as Record<string, string | null>)) {
                            if (url) {
                                next[id] = url;
                                resolvedIds.add(id);
                            }
                        }
                        saveSessionCache(next);
                        return next;
                    });
                    queue.update(q => q.map(t => {
                        const url = (result.results as Record<string, string | null>)[t.id];
                        return url ? {...t, artworkUrl: url} : t;
                    }));
                }
            } catch { /* silent */ } finally {
                chunk.forEach(t => pendingIds.delete(t.id));
            }
        });
    }
}
