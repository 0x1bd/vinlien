import {get, writable} from 'svelte/store';
import {
    queue,
    currentTrackIndex,
    currentTrack,
    isPlaying,
    volume,
    isMuted,
    repeatMode,
    user,
    useRecommendations,
    resolvedStreamUrl,
    resolvedStreamProvider
} from '$lib/utils/store';
import {apiRequest} from '$lib/utils/api';
import {getCachedTrackUrl} from '$lib/utils/offlineAudio';
import {enrichArtwork} from '$lib/utils/artworkEnrich';
import {addToast} from '$lib/utils/toast';
import type {Track} from '$lib/utils/types';

export const audioProgress = writable(0);
export const currentTimeDisplay = writable("0:00");
export const durationDisplay = writable("0:00");

const REPEAT_OFF = 0;
const REPEAT_ALL = 1;
const REPEAT_ONE = 2;

const PRELOAD_SECONDS_BEFORE_END = 15;

class AudioManager {
    private audio = new Audio();
    private preloadAudio = new Audio();
    private preloadedTrackId: string | null = null;
    private preloadAttemptedForTrack: string | null = null;
    private isFetchingRec = false;
    private lastPositionReportSec = -1;
    private currentBlobUrl: string | null = null;
    private loadingForTrackId: string | null = null;
    private streamRetried = new Set<string>();
    private trackStartTimeMs = 0;

    constructor() {
        this.audio.autoplay = true;
        this.preloadAudio.preload = 'auto';

        user.subscribe(u => {
            if (!u) {
                this.audio.pause();
                this.preloadAudio.removeAttribute('src');
            }
        });

        this.audio.addEventListener('timeupdate', () => {
            const cur = this.audio.currentTime || 0;
            const dur = (this.audio.duration && Number.isFinite(this.audio.duration)) ? this.audio.duration : 1;
            audioProgress.set((cur / dur) * 100);
            currentTimeDisplay.set(this.formatTime(cur));
            durationDisplay.set(this.formatTime(dur));

            const curSec = Math.floor(cur);
            if (curSec !== this.lastPositionReportSec) {
                this.lastPositionReportSec = curSec;
                this.updatePositionState();
            }

            const remaining = dur - cur;
            if (dur > 1 && remaining <= PRELOAD_SECONDS_BEFORE_END && remaining > 0) {
                this.preloadNextTrackIfNeeded();
            }
        });

        this.audio.addEventListener('canplay', () => {
            if (this.loadingForTrackId) this.streamRetried.delete(this.loadingForTrackId);
        });

        this.audio.addEventListener('ended', () => this.playNext());

        this.audio.addEventListener('error', async () => {
            const failedTrackId = this.loadingForTrackId;
            if (!this.audio.src || !get(isPlaying) || !failedTrackId) return;

            if (!this.streamRetried.has(failedTrackId)) {
                this.streamRetried.add(failedTrackId);
                try { await fetch('/api/auth/refresh', {method: 'POST', credentials: 'include'}); } catch {}
                if (this.loadingForTrackId === failedTrackId) {
                    const q = get(queue);
                    const idx = get(currentTrackIndex);
                    const track = q[idx];
                    if (track?.id === failedTrackId) {
                        this.loadTrack(track);
                        return;
                    }
                }
            }

            console.warn('[audio] Stream failed, skipping to next track', this.audio.error?.message);
            this.playNext(true);
        });

        this.audio.addEventListener('durationchange', () => this.updatePositionState());
        this.audio.addEventListener('seeked', () => this.updatePositionState());
        this.audio.addEventListener('play', () => this.updatePositionState());

        isPlaying.subscribe(play => {
            if (play && this.audio.src) this.audio.play().catch(() => {});
            else this.audio.pause();
            window.vinlienElectron?.updatePlayState?.(play);
        });

        volume.subscribe(v => this.audio.volume = v);
        isMuted.subscribe(m => this.audio.muted = m);

        currentTrack.subscribe(track => {
            if (track) {
                if (this.loadingForTrackId === track.id) {
                    if (get(isPlaying) && this.audio.src) this.audio.play().catch(() => {});
                    return;
                }

                this.trackStartTimeMs = Date.now();
                this.preloadAttemptedForTrack = null;
                this.lastPositionReportSec = -1;

                audioProgress.set(0);
                currentTimeDisplay.set("0:00");
                durationDisplay.set("0:00");

                apiRequest('/api/history', {method: 'POST', body: track}).catch(() => {});
                this.reportPlaybackEvent(track, 'started', 0, false).catch(() => {});

                this.loadTrack(track);

                if ('mediaSession' in navigator) {
                    navigator.mediaSession.metadata = new MediaMetadata({
                        title: track.title,
                        artist: track.artist,
                        album: track.albumTitle || '',
                        artwork: [{src: track.artworkUrl || '', sizes: '512x512', type: 'image/jpeg'}]
                    });

                    navigator.mediaSession.setActionHandler('play', () => isPlaying.set(true));
                    navigator.mediaSession.setActionHandler('pause', () => isPlaying.set(false));
                    navigator.mediaSession.setActionHandler('previoustrack', () => this.prev());
                    navigator.mediaSession.setActionHandler('nexttrack', () => this.playNext(true));
                    navigator.mediaSession.setActionHandler('seekto', (details) => {
                        if (details.seekTime !== undefined && details.seekTime !== null) {
                            this.audio.currentTime = details.seekTime;
                        }
                    });
                }

                if (window.vinlienElectron) {
                    window.vinlienElectron.updateMedia({
                        title: track.title,
                        artist: track.artist,
                        album: track.albumTitle || undefined,
                        artwork: track.artworkUrl || null
                    });
                }

                if (!track.artworkUrl || track.artworkUrl.includes('ytimg.com')) {
                    enrichArtwork(track).catch(() => {});
                }

                this.populateRecommendationsIfNeeded().catch(console.error);
            } else {
                this.loadingForTrackId = null;
                this.audio.pause();
                this.audio.removeAttribute('src');
                if (this.currentBlobUrl) {
                    URL.revokeObjectURL(this.currentBlobUrl);
                    this.currentBlobUrl = null;
                }

                if (window.vinlienElectron) {
                    window.vinlienElectron.updateMedia({title: '', artist: ''});
                }
            }
        });
    }

    private async loadTrack(track: Track): Promise<void> {
        this.loadingForTrackId = track.id;

        if (this.currentBlobUrl) {
            URL.revokeObjectURL(this.currentBlobUrl);
            this.currentBlobUrl = null;
        }

        const cachedUrl = await getCachedTrackUrl(track.id);

        if (this.loadingForTrackId !== track.id) {
            if (cachedUrl) URL.revokeObjectURL(cachedUrl);
            return;
        }

        if (cachedUrl) {
            this.currentBlobUrl = cachedUrl;
            this.audio.src = cachedUrl;
            resolvedStreamUrl.set('offline');
            resolvedStreamProvider.set('Offline');
            if (get(isPlaying)) this.audio.play().catch(() => {});
        } else {
            const loaded = await this.resolveAndLoadStream(track);
            if (loaded && get(isPlaying)) this.audio.play().catch(() => {});
        }
    }

    private async resolveAndLoadStream(track: Track): Promise<boolean> {
        try {
            const params = new URLSearchParams({
                id: track.id,
                artist: track.artist,
                title: track.title,
                durationMs: String(track.durationMs || 0),
            });
            if (track.streamUrl) params.set('streamUrl', track.streamUrl);

            const resp = await fetch(`/api/stream?${params}`);
            if (this.loadingForTrackId !== track.id) return false;

            if (resp.ok) {
                const data = await resp.json();
                if (this.loadingForTrackId !== track.id) return false;
                resolvedStreamUrl.set(data.streamUrl);
                resolvedStreamProvider.set(data.provider);
                this.audio.src = data.streamUrl;
                return true;
            } else {
                let errorMsg = `Cannot play "${track.title}"`;
                try {
                    const errorBody = await resp.json();
                    if (errorBody.details) errorMsg += `: ${errorBody.details}`;
                    else if (errorBody.error) errorMsg += `: ${errorBody.error}`;
                } catch {}
                console.warn('[audio] Stream resolution failed:', errorMsg);
                addToast(errorMsg, 'error');
                resolvedStreamUrl.set(null);
                resolvedStreamProvider.set(null);
                if (this.loadingForTrackId === track.id) this.loadingForTrackId = null;
            }
        } catch (e) {
            if (this.loadingForTrackId !== track.id) return false;
            console.error('[audio] Stream resolution error:', e);
            addToast(`Cannot play "${track.title}": Network error`, 'error');
            resolvedStreamUrl.set(null);
            resolvedStreamProvider.set(null);
            this.loadingForTrackId = null;
        }
        return false;
    }

    private updatePositionState() {
        const isValidNumber = !isNaN(this.audio.duration) && Number.isFinite(this.audio.duration);

        if ('mediaSession' in navigator && isValidNumber) {
            navigator.mediaSession.setPositionState({
                duration: this.audio.duration,
                playbackRate: this.audio.playbackRate,
                position: this.audio.currentTime
            });
        }

        if (window.vinlienElectron && isValidNumber) {
            window.vinlienElectron.updatePosition({
                duration: this.audio.duration,
                position: this.audio.currentTime
            });
        }
    }

    private currentPlayedMs(): number {
        if (this.audio.currentTime && Number.isFinite(this.audio.currentTime)) {
            return Math.max(0, Math.round(this.audio.currentTime * 1000));
        }
        return this.trackStartTimeMs > 0 ? Math.max(0, Date.now() - this.trackStartTimeMs) : 0;
    }

    private currentDurationMs(track: Track): number {
        if (this.audio.duration && Number.isFinite(this.audio.duration)) {
            return Math.max(0, Math.round(this.audio.duration * 1000));
        }
        return track.durationMs || 0;
    }

    private async reportPlaybackEvent(
        track: Track,
        eventType: 'started' | 'completed' | 'advanced' | 'skip_requested',
        playedMs = this.currentPlayedMs(),
        wasManual = false
    ): Promise<void> {
        await apiRequest('/api/rec/play-event', {
            method: 'POST',
            body: {
                track,
                eventType,
                playedMs,
                durationMs: this.currentDurationMs(track),
                source: get(useRecommendations) ? 'radio' : 'manual',
                wasManual
            }
        });
    }

    private async populateRecommendationsIfNeeded(): Promise<void> {
        if (this.isFetchingRec || !get(useRecommendations)) return;

        const q = get(queue);
        const idx = get(currentTrackIndex);
        
        const aheadCount = q.length - idx - 1;
        if (aheadCount >= 10) return;
        
        const needed = 10 - aheadCount;
        const seedTrack = q[q.length - 1] || q[idx];
        if (!seedTrack) return;

        this.isFetchingRec = true;
        try {
            const sessionArtists = q.slice(0, idx + 1).map(t => t.artist.toLowerCase());
            const rec = await apiRequest('/api/radio', {
                method: 'POST', 
                body: {
                    seedTrack, 
                    queue: q, 
                    sessionArtists, 
                    queueSize: needed
                }
            });
            if (rec && rec.tracks) {
                queue.update(prev => [...prev, ...rec.tracks.map((r: any) => r.track)]);
            }
        } catch (e) {
            console.error("Failed to fetch recommendations", e);
        } finally {
            this.isFetchingRec = false;
        }
    }

    private async preloadNextTrackIfNeeded() {
        if (this.isFetchingRec) return;

        let q = get(queue);
        let idx = get(currentTrackIndex);
        const rm = get(repeatMode);
        const currentTrackId = q[idx]?.id;

        if (!currentTrackId || this.preloadAttemptedForTrack === currentTrackId) return;
        this.preloadAttemptedForTrack = currentTrackId;

        if (rm === 0 && get(useRecommendations)) {
            await this.populateRecommendationsIfNeeded();
            q = get(queue);
            idx = get(currentTrackIndex);
        }

        let nextTrack: Track | null = null;

        if (rm === REPEAT_ONE) nextTrack = q[idx];
        else if (idx + 1 < q.length) nextTrack = q[idx + 1];
        else if (rm === REPEAT_ALL) nextTrack = q[0];

        if (nextTrack && this.preloadedTrackId !== nextTrack.id) {
            this.preloadedTrackId = nextTrack.id;
            const resolvedUrl = await this.resolveStreamUrl(nextTrack);
            if (resolvedUrl) {
                this.preloadAudio.src = resolvedUrl;
            }
        }
    }

    private async resolveStreamUrl(track: Track): Promise<string | null> {
        try {
            const params = new URLSearchParams({
                id: track.id,
                artist: track.artist,
                title: track.title,
                durationMs: String(track.durationMs || 0),
            });
            if (track.streamUrl) params.set('streamUrl', track.streamUrl);

            const resp = await fetch(`/api/stream?${params}`);
            if (resp.ok) {
                const data = await resp.json();
                return data.streamUrl;
            } else {
                try {
                    const errorBody = await resp.json();
                    console.warn(`[audio] Preload stream resolution failed for '${track.title}': ${errorBody.details || errorBody.error || 'unknown'}`);
                } catch {}
            }
        } catch (e) {
            console.warn('[audio] Preload stream resolution error:', e);
        }
        return null;
    }

    async playNext(force = false) {
        if (this.isFetchingRec) return;

        const q = get(queue);
        const rm = get(repeatMode);
        const idx = get(currentTrackIndex);
        const track = q[idx];

        if (track && this.trackStartTimeMs > 0) {
            const playedMs = this.currentPlayedMs();
            const durationMs = this.currentDurationMs(track);
            const completion = durationMs > 0 ? playedMs / durationMs : 0;
            const eventType = force ? 'skip_requested' : (completion >= 0.8 ? 'completed' : 'advanced');
            this.reportPlaybackEvent(track, eventType, playedMs, force).catch(() => {});

            if (force) {
                apiRequest('/api/rec/skip', {
                    method: 'POST',
                    body: {trackId: track.id, artist: track.artist, playedMs}
                }).catch(() => {});
            }
        }

        if (rm === REPEAT_ONE && !force) {
            this.audio.currentTime = 0;
            this.audio.play().catch(() => {});
            return;
        }

        if (idx + 1 < q.length) {
            currentTrackIndex.set(idx + 1);
            if (rm === 0 && get(useRecommendations)) {
                this.populateRecommendationsIfNeeded().catch(console.error);
            }
            return;
        }

        if (rm === REPEAT_ALL && !force) {
            currentTrackIndex.set(0);
            return;
        }

        if (!get(useRecommendations)) {
            isPlaying.set(false);
            audioProgress.set(0);
            return;
        }

        await this.populateRecommendationsIfNeeded();
        
        const newQ = get(queue);
        if (idx + 1 < newQ.length) {
            currentTrackIndex.set(idx + 1);
            this.populateRecommendationsIfNeeded().catch(console.error);
        } else {
            isPlaying.set(false);
            audioProgress.set(0);
        }
    }

    prev() {
        if (this.audio.currentTime > 3) {
            this.audio.currentTime = 0;
        } else {
            const idx = get(currentTrackIndex);
            if (idx > 0) currentTrackIndex.set(idx - 1);
        }
    }

    invalidateBlobs(trackIds: string[]): void {
        const current = get(queue)[get(currentTrackIndex)];
        if (current && trackIds.includes(current.id)) {
            if (this.currentBlobUrl) {
                URL.revokeObjectURL(this.currentBlobUrl);
                this.currentBlobUrl = null;
            }
            this.loadingForTrackId = null;
            this.audio.removeAttribute('src');
        }
    }

    seek(percent: number) {
        if (!this.audio.duration || !Number.isFinite(this.audio.duration)) return;
        this.audio.currentTime = (percent / 100) * this.audio.duration;
    }

    seekToSeconds(seconds: number) {
        this.audio.currentTime = seconds;
    }

    private formatTime(secs: number) {
        if (isNaN(secs) || !Number.isFinite(secs)) return "0:00";
        const m = Math.floor(secs / 60);
        const s = Math.floor(secs % 60).toString().padStart(2, '0');
        return `${m}:${s}`;
    }
}

export const audioManager = new AudioManager();

if (typeof window !== 'undefined') {
    window.vinlienControl = {
        play: () => isPlaying.set(true),
        pause: () => isPlaying.set(false),
        togglePlay: () => isPlaying.update(p => !p),
        next: () => audioManager.playNext(true),
        prev: () => audioManager.prev(),
        seekTo: (seconds: number) => audioManager.seekToSeconds(seconds),
    };
}
