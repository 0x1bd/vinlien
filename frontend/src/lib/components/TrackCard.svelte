<script lang="ts">
    import {goto} from '$app/navigation';
    import type {Track} from '$lib/utils/types';

    export let track: Track;
    export let onPlay: () => void;
</script>

<!-- svelte-ignore a11y-click-events-have-key-events -->
<!-- svelte-ignore a11y-no-static-element-interactions -->
<div class="track-card" on:click={onPlay}>
    <div class="img-wrapper">
        <img src={track.artworkUrl} alt="art">
        <button class="play-overlay">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
                <path d="M8 5v14l11-7z"/>
            </svg>
        </button>
    </div>
    <div class="info">
        <div class="title">{track.title}</div>
        <!-- svelte-ignore a11y-click-events-have-key-events -->
        <!-- svelte-ignore a11y-no-static-element-interactions -->
        <div
                class="artist artist-link"
                on:click|stopPropagation={() => goto(`/artist/${encodeURIComponent(track.artist)}`)}
        >
            {track.artist}
        </div>
    </div>
</div>

<style>
    .track-card {
        background: var(--bg-elevated);
        padding: 16px;
        border-radius: 8px;
        cursor: pointer;
        transition: 0.3s;
    }

    .track-card:hover {
        background: var(--bg-hover);
        transform: translateY(-4px);
    }

    .img-wrapper {
        position: relative;
        width: 100%;
        aspect-ratio: 1;
        margin-bottom: 16px;
    }

    img {
        width: 100%;
        height: 100%;
        object-fit: cover;
        border-radius: 6px;
        box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
    }

    .play-overlay {
        position: absolute;
        bottom: 8px;
        right: 8px;
        width: 48px;
        height: 48px;
        padding: 0;
        border-radius: 50%;
        background: var(--accent-color);
        color: #fff;
        display: flex;
        align-items: center;
        justify-content: center;
        opacity: 0;
        transform: translateY(8px);
        transition: 0.3s;
        box-shadow: 0 8px 16px rgba(0, 0, 0, 0.3);
    }

    .track-card:hover .play-overlay {
        opacity: 1;
        transform: translateY(0);
    }

    .title {
        font-weight: 700;
        font-size: 15px;
        margin-bottom: 4px;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
    }

    .artist {
        font-size: 13px;
        color: var(--text-secondary);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        display: inline-block;
        max-width: 100%;
    }

    @media (max-width: 600px) {
        .play-overlay {
            opacity: 1;
            transform: translateY(0);
        }
    }
</style>