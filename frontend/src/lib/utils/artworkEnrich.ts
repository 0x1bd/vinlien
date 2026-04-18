import {writable} from 'svelte/store';
import {queue} from '$lib/utils/store';
import {apiRequest} from '$lib/utils/api';
import type {Track} from '$lib/utils/types';

export const enrichedArtworkByTrackId = writable<Record<string, string>>({});

const pendingEnrich = new Set<string>();

export async function enrichArtwork(track: Track): Promise<void> {
    if (pendingEnrich.has(track.id)) return;
    pendingEnrich.add(track.id);
    try {
        const result = await apiRequest('/api/artwork/enrich', {
            method: 'POST',
            body: {id: track.id, title: track.title, artist: track.artist}
        });
        if (result?.artworkUrl) {
            queue.update(q => q.map(t => t.id === track.id ? {...t, artworkUrl: result.artworkUrl} : t));
            enrichedArtworkByTrackId.update(m => ({...m, [track.id]: result.artworkUrl}));
        }
    } finally {
        pendingEnrich.delete(track.id);
    }
}
