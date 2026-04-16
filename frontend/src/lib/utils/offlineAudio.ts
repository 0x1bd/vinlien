import {browser} from '$app/environment';
import {writable} from 'svelte/store';
import type {Playlist, Track} from '$lib/utils/types';

const OFFLINE_AUDIO_CACHE = 'vinlien-offline-audio-v1';
const PLAYLIST_LINKS_KEY = 'vinlien-offline-links';
const CONCURRENT_DOWNLOADS = 3;

export interface DownloadProgress {
    downloaded: number;
    total: number;
    failed: number;
    currentTrackTitle?: string;
}

export const activeDownloads = writable<Record<string, DownloadProgress>>({});

const inProgressDownloads = new Set<string>();

function getLinks(): Record<string, string[]> {
    if (!browser) return {};
    try {
        return JSON.parse(localStorage.getItem(PLAYLIST_LINKS_KEY) || '{}');
    } catch {
        return {};
    }
}

function saveLinks(links: Record<string, string[]>): void {
    if (browser) localStorage.setItem(PLAYLIST_LINKS_KEY, JSON.stringify(links));
}

function setPlaylistLinks(playlistId: string, trackIds: string[]): void {
    const links = getLinks();
    links[playlistId] = trackIds;
    saveLinks(links);
}

function removePlaylistLinks(playlistId: string): string[] {
    const links = getLinks();
    const removed = links[playlistId] ?? [];
    delete links[playlistId];
    saveLinks(links);
    const stillLinked = new Set(Object.values(links).flat());
    return removed.filter(id => !stillLinked.has(id));
}

function buildStreamUrl(track: Track): string {
    const params = new URLSearchParams({
        id: track.id,
        title: track.title,
        artist: track.artist,
        durationMs: String(track.durationMs)
    });
    if (track.streamUrl) params.set('streamUrl', track.streamUrl);
    return `/api/stream?${params.toString()}`;
}

function cacheRequestForTrack(trackId: string): Request {
    if (!browser) {
        return new Request(`https://offline.invalid/${encodeURIComponent(trackId)}`);
    }
    const url = new URL(`/__offline_audio__/${encodeURIComponent(trackId)}`, window.location.origin);
    return new Request(url.toString(), {method: 'GET'});
}

async function getCache(): Promise<Cache | null> {
    if (!browser || typeof window.caches === 'undefined') return null;
    return caches.open(OFFLINE_AUDIO_CACHE);
}

export async function isTrackCached(trackId: string): Promise<boolean> {
    const cache = await getCache();
    if (!cache) return false;
    return !!(await cache.match(cacheRequestForTrack(trackId)));
}

export async function getCachedTrackBlob(trackId: string): Promise<Blob | null> {
    const cache = await getCache();
    if (!cache) return null;
    const entry = await cache.match(cacheRequestForTrack(trackId));
    if (!entry) return null;
    return entry.blob();
}

export async function getCachedTrackUrl(trackId: string): Promise<string | null> {
    const blob = await getCachedTrackBlob(trackId);
    if (!blob) return null;
    return URL.createObjectURL(blob);
}

export async function getPlaylistOfflineStats(playlist: Playlist): Promise<{cached: number; total: number}> {
    const uniqueTrackIds = [...new Set(playlist.tracks.map(t => t.id))];
    if (!uniqueTrackIds.length) return {cached: 0, total: 0};

    const linkedIds = new Set(getLinks()[playlist.id] ?? []);
    if (linkedIds.size === 0) return {cached: 0, total: uniqueTrackIds.length};

    const cache = await getCache();
    if (!cache) return {cached: 0, total: uniqueTrackIds.length};

    let cached = 0;
    for (const id of uniqueTrackIds) {
        if (linkedIds.has(id) && await cache.match(cacheRequestForTrack(id))) cached++;
    }
    return {cached, total: uniqueTrackIds.length};
}

export async function downloadTrack(track: Track): Promise<void> {
    const cache = await getCache();
    if (!cache) return;
    const cacheKey = cacheRequestForTrack(track.id);
    if (await cache.match(cacheKey)) return;
    const response = await fetch(buildStreamUrl(track), {
        method: 'GET',
        credentials: 'include',
        headers: {'Range': 'bytes=0-', 'Accept-Encoding': 'identity'}
    });
    if (!response.ok && response.status !== 206) throw new Error(`HTTP ${response.status}`);
    const blob = await response.blob();
    await cache.put(cacheKey, new Response(blob, {
        status: 200,
        headers: {'Content-Type': response.headers.get('Content-Type') || 'audio/mpeg'}
    }));
}

export async function downloadPlaylistAudio(
    playlist: Playlist
): Promise<{downloaded: number; total: number; failed: number}> {
    if (inProgressDownloads.has(playlist.id)) return {downloaded: 0, total: 0, failed: 0};

    const uniqueTracks = [...new Map(playlist.tracks.map(t => [t.id, t])).values()];
    const total = uniqueTracks.length;
    if (!total) return {downloaded: 0, total: 0, failed: 0};

    const cache = await getCache();
    if (!cache) throw new Error('Offline caching is not available in this browser.');

    let downloaded = 0, failed = 0;
    inProgressDownloads.add(playlist.id);
    activeDownloads.update(m => ({...m, [playlist.id]: {downloaded, total, failed}}));

    const downloadOne = async (track: Track) => {
        try {
            await downloadTrack(track);
            downloaded++;
        } catch (e) {
            console.error('[offline] Failed to cache track', track.id, e);
            failed++;
        }
        activeDownloads.update(m => ({
            ...m,
            [playlist.id]: {downloaded, total, failed, currentTrackTitle: track.title}
        }));
    };

    try {
        for (let i = 0; i < uniqueTracks.length; i += CONCURRENT_DOWNLOADS) {
            await Promise.all(uniqueTracks.slice(i, i + CONCURRENT_DOWNLOADS).map(downloadOne));
        }
    } finally {
        inProgressDownloads.delete(playlist.id);
        activeDownloads.update(({[playlist.id]: _, ...rest}) => rest);
    }

    setPlaylistLinks(playlist.id, uniqueTracks.map(t => t.id));

    return {downloaded, total, failed};
}

export async function removePlaylistAudio(playlist: Playlist): Promise<void> {
    const cache = await getCache();
    if (!cache) return;
    const orphaned = removePlaylistLinks(playlist.id);
    await Promise.all(orphaned.map(id => cache.delete(cacheRequestForTrack(id))));
}
