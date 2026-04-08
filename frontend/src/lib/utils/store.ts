import {writable, derived} from 'svelte/store';
import {browser} from '$app/environment';
import type {Track, User, Playlist} from '$lib/utils/types';

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

const initialUser = browser ? JSON.parse(localStorage.getItem('user') || 'null') : null;
export const user = writable<User | null>(initialUser);

if (browser) {
    user.subscribe(value => {
        if (value) localStorage.setItem('user', JSON.stringify(value));
        else localStorage.removeItem('user');
    });
}

export const userPlaylists = writable<Playlist[]>([]);
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

export const audioProvidersOrder = createPersistedStore<string[]>('vinlien_audioProvidersOrder', []);
export const metaProvidersOrder = createPersistedStore<string[]>('vinlien_metaProvidersOrder', []);

export const currentTrack = derived(
    [queue, currentTrackIndex],
    ([$queue, $currentTrackIndex]) => $queue[$currentTrackIndex] || null
);