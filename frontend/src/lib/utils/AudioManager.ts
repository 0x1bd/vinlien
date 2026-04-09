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
    silenceSkip,
    silenceSkipThreshold
} from '$lib/utils/store';
import {apiRequest} from '$lib/utils/api';
import type {Track} from '$lib/utils/types';

export const audioProgress = writable(0);
export const currentTimeDisplay = writable("0:00");
export const durationDisplay = writable("0:00");

class AudioManager {
    private audio = new Audio();
    private preloadAudio = new Audio();
    private preloadedTrackId: string | null = null;
    private preloadAttemptedForTrack: string | null = null;
    private isFetchingRec = false;

    private audioContext: AudioContext | null = null;
    private analyserNode: AnalyserNode | null = null;
    private analyserBuffer: Float32Array | null = null;
    private silenceStartTime: number | null = null;

    constructor() {
        this.audio.autoplay = true;
        this.preloadAudio.preload = 'auto';

        user.subscribe(u => {
            if (!u) {
                this.audio.pause();
                this.preloadAudio.removeAttribute('src');
            }
        });

        this.audio.addEventListener('play', () => {
            if (this.audioContext?.state === 'suspended') {
                this.audioContext.resume().catch(() => {
                });
            }
        });

        this.audio.addEventListener('timeupdate', () => {
            const cur = this.audio.currentTime || 0;
            const dur = (this.audio.duration && Number.isFinite(this.audio.duration)) ? this.audio.duration : 1;
            audioProgress.set((cur / dur) * 100);
            currentTimeDisplay.set(this.formatTime(cur));
            durationDisplay.set(this.formatTime(dur));

            const remaining = dur - cur;
            if (dur > 1 && remaining <= 15 && remaining > 0) {
                this.preloadNextTrackIfNeeded();
            }

            if (get(silenceSkip) && dur > 1) {
                if (!this.audioContext) this.initAudioContext();
                if (this.analyserNode) {
                    const rms = this.getRms();
                    const threshold = get(silenceSkipThreshold);
                    if (rms < 0.005) {
                        if (this.silenceStartTime === null) this.silenceStartTime = cur;
                        else if (cur - this.silenceStartTime >= threshold) {
                            this.silenceStartTime = null;
                            if (cur < dur / 2) this.audio.currentTime = cur + threshold;
                            else this.NoNoplayNext();
                        }
                    } else {
                        this.silenceStartTime = null;
                    }
                }
            }
        });

        this.audio.addEventListener('ended', () => this.playNext());

        isPlaying.subscribe(play => {
            if (play && this.audio.src) this.audio.play().catch(() => {
            });
            else this.audio.pause();
        });

        volume.subscribe(v => this.audio.volume = v);
        isMuted.subscribe(m => this.audio.muted = m);

        currentTrack.subscribe(track => {
            if (track) {
                this.preloadAttemptedForTrack = null;
                this.silenceStartTime = null;

                const currentSrcId = new URL(this.audio.src || 'http://localhost').searchParams.get('id');
                if (currentSrcId === track.id && this.audio.readyState > 0) {
                    if (get(isPlaying)) this.audio.play().catch(() => {
                    });
                    return;
                }

                apiRequest('/api/history', {method: 'POST', body: track})
                    .catch(e => console.error("Failed to record history", e));

                const streamUrlParam = track.streamUrl ? `&streamUrl=${encodeURIComponent(track.streamUrl)}` : '';
                this.audio.src = `/api/stream?id=${encodeURIComponent(track.id)}&title=${encodeURIComponent(track.title)}&artist=${encodeURIComponent(track.artist)}&durationMs=${track.durationMs}${streamUrlParam}`;
                this.audio.load();

                if (get(isPlaying)) this.audio.play().catch(() => {
                });

                if ('mediaSession' in navigator) {
                    navigator.mediaSession.metadata = new MediaMetadata({
                        title: track.title,
                        artist: track.artist,
                        album: 'Vinlien',
                        artwork: [{src: track.artworkUrl || '', sizes: '512x512', type: 'image/jpeg'}]
                    });
                    navigator.mediaSession.setActionHandler('play', () => isPlaying.set(true));
                    navigator.mediaSession.setActionHandler('pause', () => isPlaying.set(false));
                    navigator.mediaSession.setActionHandler('previoustrack', () => this.prev());
                    navigator.mediaSession.setActionHandler('nexttrack', () => this.playNext(true));
                }
            } else {
                this.audio.pause();
                this.audio.removeAttribute('src');
            }
        });
    }

    private initAudioContext() {
        try {
            this.audioContext = new AudioContext();
            const source = this.audioContext.createMediaElementSource(this.audio);
            this.analyserNode = this.audioContext.createAnalyser();
            this.analyserNode.fftSize = 2048;
            this.analyserBuffer = new Float32Array(this.analyserNode.fftSize);
            source.connect(this.analyserNode);
            this.analyserNode.connect(this.audioContext.destination);
        } catch (e) {
            console.warn('[silence] AudioContext init failed:', e);
            this.audioContext = null;
            this.analyserNode = null;
        }
    }

    private getRms(): number {
        if (!this.analyserNode || !this.analyserBuffer) return 1;
        this.analyserNode.getFloatTimeDomainData(this.analyserBuffer);
        let sum = 0;
        for (const s of this.analyserBuffer) sum += s * s;
        return Math.sqrt(sum / this.analyserBuffer.length);
    }

    private async preloadNextTrackIfNeeded() {
        if (this.isFetchingRec) return;

        const q = get(queue);
        const idx = get(currentTrackIndex);
        const rm = get(repeatMode);
        const currentTrackId = q[idx]?.id;

        if (!currentTrackId || this.preloadAttemptedForTrack === currentTrackId) return;
        this.preloadAttemptedForTrack = currentTrackId;

        let nextTrack: Track | null = null;
        let fetchRecs = false;

        if (rm === 2) nextTrack = q[idx];
        else if (idx + 1 < q.length) nextTrack = q[idx + 1];
        else if (rm === 1) nextTrack = q[0];
        else fetchRecs = true;

        if (fetchRecs) {
            this.isFetchingRec = true;
            try {
                const recs = await apiRequest('/api/recommendations', {method: 'POST', body: {queue: q}});
                if (recs?.length > 0) {
                    nextTrack = recs[0];
                    queue.update(q => [...q, nextTrack!]);
                }
            } catch (e) {
                console.error("Failed to fetch recommendation", e);
            } finally {
                this.isFetchingRec = false;
            }
        }

        if (nextTrack && this.preloadedTrackId !== nextTrack.id) {
            this.preloadedTrackId = nextTrack.id;
            const streamUrlParam = nextTrack.streamUrl ? `&streamUrl=${encodeURIComponent(nextTrack.streamUrl)}` : '';
            this.preloadAudio.src = `/api/stream?id=${encodeURIComponent(nextTrack.id)}&title=${encodeURIComponent(nextTrack.title)}&artist=${encodeURIComponent(nextTrack.artist)}&durationMs=${nextTrack.durationMs}${streamUrlParam}`;
            this.preloadAudio.load();
        }
    }

    async playNext(force = false) {
        if (this.isFetchingRec) return;

        const q = get(queue);
        const rm = get(repeatMode);
        const idx = get(currentTrackIndex);

        if (rm === 2 && !force) {
            this.audio.currentTime = 0;
            this.audio.play().catch(() => {
            });
            return;
        }

        if (idx + 1 < q.length) {
            currentTrackIndex.set(idx + 1);
            return;
        }

        if (rm === 1 && !force) {
            currentTrackIndex.set(0);
            return;
        }

        this.isFetchingRec = true;
        try {
            const recs = await apiRequest('/api/recommendations', {method: 'POST', body: {queue: q}});
            if (recs?.length > 0) {
                queue.update(q => [...q, recs[0]]);
                currentTrackIndex.set(idx + 1);
            } else {
                isPlaying.set(false);
                audioProgress.set(0);
            }
        } catch (e) {
            console.error("Failed to fetch recommendation", e);
            isPlaying.set(false);
            audioProgress.set(0);
        } finally {
            this.isFetchingRec = false;
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

    seek(percent: number) {
        if (!this.audio.duration || !Number.isFinite(this.audio.duration)) return;
        this.audio.currentTime = (percent / 100) * this.audio.duration;
    }

    private formatTime(secs: number) {
        if (isNaN(secs) || !Number.isFinite(secs)) return "0:00";
        const m = Math.floor(secs / 60);
        const s = Math.floor(secs % 60).toString().padStart(2, '0');
        return `${m}:${s}`;
    }
}

export const audioManager = new AudioManager();
