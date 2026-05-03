import {writable, derived, get} from 'svelte/store';
import {addToast} from '$lib/utils/toast';
import {browser} from '$app/environment';
import type {Track, User, Playlist} from '$lib/utils/types';
import type {ThemeId} from '$lib/utils/themes';

function createPersistedStore<T>(key: string, initialValue: T) {
    let storedValue = initialValue;
    if (browser) {
        const item = localStorage.getItem(key);
        if (item !== null) {
            try {
                storedValue = JSON.parse(item);
            } catch (e) {
                storedValue = initialValue;
            }
        }
    }

    const store = writable<T>(storedValue);

    if (browser) {
        store.subscribe(value => {
            localStorage.setItem(key, JSON.stringify(value));
        });
    }

    return store;
}

const initialUser = browser ? (() => { try { return JSON.parse(localStorage.getItem('user') || 'null'); } catch { return null; } })() : null;
export const user = writable<User | null>(initialUser);

if (browser) {
    user.subscribe(value => {
        if (value) localStorage.setItem('user', JSON.stringify(value));
        else localStorage.removeItem('user');
    });
}

export const userPlaylists = createPersistedStore<Playlist[]>('vinlien_playlists', []);
export const isSidebarOpen = writable(true);

export const queue = writable<Track[]>([]);
export const currentTrackIndex = writable(-1);
export const isPlaying = writable(false);

export const volume = createPersistedStore<number>('vinlien_volume', 1);
export const isMuted = createPersistedStore<boolean>('vinlien_muted', false);
export const repeatMode = createPersistedStore<number>('vinlien_repeatMode', 0);
export const showVolumeSlider = createPersistedStore<boolean>('vinlien_showVolumeSlider', false);

export const showQueuePanel = writable(false);
export const trackToAdd = writable<Track | null>(null);

export const theme = createPersistedStore<ThemeId>('vinlien_theme', 'dark');
export const useRecommendations = createPersistedStore<boolean>('vinlien_useRecommendations', true);
export const continuePlaylist = createPersistedStore<boolean>('vinlien_continuePlaylist', true);

export const currentTrack = derived(
    [queue, currentTrackIndex],
    ([$queue, $currentTrackIndex]) => $queue[$currentTrackIndex] || null
);

export const similarTracks = writable<Track[]>([]);
export const isFetchingSimilar = writable(false);
let lastSimilarSeedId = '';

export async function fetchSimilarTracksIfNeeded(track: Track) {
    if (!track || get(isFetchingSimilar)) return;
    if (lastSimilarSeedId === track.id) return;
    
    isFetchingSimilar.set(true);
    try {
        const { apiRequest } = await import('$lib/utils/api');
        const rec = await apiRequest('/api/rec/similar', {
            method: 'POST', 
            body: {
                seedTrack: track,
                queue: [], 
                sessionArtists: [],
                queueSize: 30
            }
        });
        if (rec && rec.tracks) {
            similarTracks.set(rec.tracks.map((r: any) => r.track));
            lastSimilarSeedId = track.id;
        }
    } catch (e) {
        console.error("Failed to fetch similar tracks", e);
    } finally {
        isFetchingSimilar.set(false);
    }
}

export const serverAvailable = writable(true);
export const autoDownloadPlaylists = createPersistedStore<string[]>('vinlien_autoDownload', []);

export function requireOnline(message = 'This action is not available offline'): boolean {
    if (!get(serverAvailable)) {
        addToast(message, 'error');
        return false;
    }
    return true;
}