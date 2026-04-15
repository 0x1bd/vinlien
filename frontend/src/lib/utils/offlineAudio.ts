import {browser} from '$app/environment';
import type {Playlist, Track} from '$lib/utils/types';

const OFFLINE_AUDIO_CACHE = 'vinlien-offline-audio-v1';

function buildStreamUrl(track: Track): string {
    const params = new URLSearchParams({
        id: track.id,
        title: track.title,
        artist: track.artist,
        durationMs: String(track.durationMs)
    });

    if (track.streamUrl) {
        params.set('streamUrl', track.streamUrl);
    }

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
    const entry = await cache.match(cacheRequestForTrack(trackId));
    return !!entry;
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
    const uniqueTrackIds = [...new Set(playlist.tracks.map(track => track.id))];

    if (!uniqueTrackIds.length) {
        return {cached: 0, total: 0};
    }

    const cache = await getCache();
    if (!cache) {
        return {cached: 0, total: uniqueTrackIds.length};
    }

    let cached = 0;
    for (const trackId of uniqueTrackIds) {
        if (await cache.match(cacheRequestForTrack(trackId))) {
            cached += 1;
        }
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
    if (!response.ok && response.status !== 206) return;
    const blob = await response.blob();
    await cache.put(cacheKey, new Response(blob, {
        status: 200,
        headers: {'Content-Type': response.headers.get('Content-Type') || 'audio/mpeg'}
    }));
}

const CONCURRENT_DOWNLOADS = 3;

export async function downloadPlaylistAudio(
    playlist: Playlist,
    onProgress?: (progress: {downloaded: number; total: number; failed: number; currentTrackTitle?: string}) => void
): Promise<{downloaded: number; total: number; failed: number}> {
    const uniqueTracks = [...new Map(playlist.tracks.map(track => [track.id, track])).values()];

    if (!uniqueTracks.length) {
        onProgress?.({downloaded: 0, total: 0, failed: 0});
        return {downloaded: 0, total: 0, failed: 0};
    }

    const cache = await getCache();
    if (!cache) {
        throw new Error('Offline caching is not available in this browser.');
    }

    let downloaded = 0;
    let failed = 0;
    const total = uniqueTracks.length;

    const downloadOne = async (track: Track) => {
        const cacheKey = cacheRequestForTrack(track.id);
        const cached = await cache.match(cacheKey);

        if (cached) {
            downloaded += 1;
            onProgress?.({downloaded, total, failed, currentTrackTitle: track.title});
            return;
        }

        try {
            const response = await fetch(buildStreamUrl(track), {
                method: 'GET',
                credentials: 'include',
                headers: {'Range': 'bytes=0-', 'Accept-Encoding': 'identity'}
            });

            if (!response.ok && response.status !== 206) {
                throw new Error(`HTTP ${response.status}`);
            }

            const blob = await response.blob();
            const normalized = new Response(blob, {
                status: 200,
                headers: {'Content-Type': response.headers.get('Content-Type') || 'audio/mpeg'}
            });
            await cache.put(cacheKey, normalized);
            downloaded += 1;
        } catch (error) {
            console.error('[offline] Failed to cache track', track.id, error);
            failed += 1;
        }

        onProgress?.({downloaded, total, failed, currentTrackTitle: track.title});
    };

    for (let i = 0; i < uniqueTracks.length; i += CONCURRENT_DOWNLOADS) {
        await Promise.all(uniqueTracks.slice(i, i + CONCURRENT_DOWNLOADS).map(downloadOne));
    }

    return {downloaded, total, failed};
}

export async function removePlaylistAudio(playlist: Playlist, allPlaylists: Playlist[]): Promise<void> {
    const cache = await getCache();
    if (!cache) return;

    const sharedIds = new Set(
        allPlaylists.filter(p => p.id !== playlist.id).flatMap(p => p.tracks.map(t => t.id))
    );
    const toDelete = [...new Set(playlist.tracks.map(t => t.id))].filter(id => !sharedIds.has(id));
    await Promise.all(toDelete.map(id => cache.delete(cacheRequestForTrack(id))));
}

