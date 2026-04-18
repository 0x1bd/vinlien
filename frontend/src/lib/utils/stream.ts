import type {Track} from '$lib/utils/types';

export function buildStreamUrl(track: Track): string {
    const params = new URLSearchParams({
        id: track.id,
        title: track.title,
        artist: track.artist,
        durationMs: String(track.durationMs)
    });
    if (track.streamUrl) params.set('streamUrl', track.streamUrl);
    return `/api/stream?${params}`;
}
