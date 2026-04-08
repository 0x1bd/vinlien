<script lang="ts">
    import {page} from '$app/stores';
    import {queue, currentTrackIndex, isPlaying} from '$lib/utils/store';
    import {apiRequest} from '$lib/utils/api';
    import TrackRow from '$lib/components/TrackRow.svelte';
    import type {Album} from '$lib/utils/types';

    let album: Album | null = null;

    $: if ($page.params.id) {
        apiRequest(`/api/album/${encodeURIComponent($page.params.id)}`).then(res => album = res);
    }

    function playAll() {
        if (!album?.tracks.length) return;
        $queue = [...album.tracks];
        $currentTrackIndex = 0;
        $isPlaying = true;
    }

    function playTrackAtIndex(index: number) {
        if (!album) return;
        $queue = [album.tracks[index]];
        $currentTrackIndex = 0;
        $isPlaying = true;
    }
</script>

{#if album}
    <div class="album-header">
        <img src={album.artworkUrl} alt="Album Art" class="album-art"/>
        <div class="album-meta">
            <span class="type-badge">Album</span>
            <h2 class="page-title">{album.title}</h2>
            <div class="subtitle">{album.artist} • {album.year || 'Unknown Year'} • {album.tracks.length} songs</div>

            <button class="play-all-btn" on:click={playAll}>
                <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M8 5v14l11-7z"/>
                </svg>
                Play
            </button>
        </div>
    </div>

    <div class="track-list">
        {#each album.tracks as track, i}
            <TrackRow {track} onPlay={() => playTrackAtIndex(i)}/>
        {/each}
    </div>
{:else}
    <div class="loading-state">Loading album...</div>
{/if}

<style>
    .album-header {
        display: flex;
        gap: 32px;
        align-items: flex-end;
        margin-bottom: 48px;
    }

    .album-art {
        width: 200px;
        height: 200px;
        border-radius: 8px;
        box-shadow: 0 16px 32px rgba(0, 0, 0, 0.5);
        object-fit: cover;
    }

    .album-meta {
        display: flex;
        flex-direction: column;
        gap: 8px;
    }

    .type-badge {
        font-size: 12px;
        font-weight: 700;
        text-transform: uppercase;
        color: var(--text-secondary);
        letter-spacing: 0.1em;
    }

    .page-title {
        font-size: 48px;
        line-height: 1.1;
        margin-bottom: 8px;
    }

    .subtitle {
        color: var(--text-secondary);
        font-size: 14px;
        margin-bottom: 16px;
    }

    .play-all-btn {
        display: flex;
        align-items: center;
        gap: 8px;
        width: fit-content;
        background: var(--accent-color);
        color: #fff;
        padding: 12px 32px;
        font-size: 16px;
    }

    .play-all-btn:hover {
        background: #1d4ed8;
    }

    .track-list {
        display: flex;
        flex-direction: column;
        gap: 2px;
    }

    .loading-state {
        color: var(--text-secondary);
        padding: 40px 0;
        text-align: center;
    }

    @media (max-width: 768px) {
        .album-header {
            flex-direction: column;
            align-items: center;
            text-align: center;
            gap: 24px;
        }

        .page-title {
            font-size: 32px;
        }

        .play-all-btn {
            margin: 0 auto;
        }
    }
</style>