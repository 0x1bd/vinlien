<script lang="ts">
    import {goto} from '$app/navigation';
    import type {Track} from '$lib/utils/types';
    import ArtworkImage from './ArtworkImage.svelte';

    export let track: Track;
    export let onPlay: () => void;
    export let subtitle: string | undefined = undefined;
</script>

<div class="track-card" role="button" tabindex="0"
     on:click={onPlay}
     on:keydown={e => (e.key === 'Enter' || e.key === ' ') && onPlay()}>
    <div class="img-wrapper">
        <ArtworkImage {track}>
            {track.title[0]?.toUpperCase() ?? '?'}
        </ArtworkImage>
    </div>
    <div class="info">
        <div class="title">{track.title}</div>
        <div class="artist">
            {#each track.artists as name, i}<button class="artist-link"
                    on:click|stopPropagation={() => goto(`/artist/${encodeURIComponent(name)}`)}>{name}</button>{#if i < track.artists.length - 1}{' & '}{/if}
            {/each}
        </div>
        {#if subtitle}
            <div class="subtitle">{subtitle}</div>
        {/if}
    </div>
    <button class="play-overlay" aria-label={`Play ${track.title}`}>
        <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
            <path d="M8 5v14l11-7z"/>
        </svg>
    </button>
</div>

<style>
    .track-card {
        background: var(--bg-elevated);
        padding: 16px;
        border-radius: 8px;
        cursor: pointer;
        transition: 0.3s;
        position: relative;
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

    .img-wrapper :global(.art-root) {
        border-radius: 6px;
        box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
        font-size: 48px;
        font-weight: 800;
        color: rgba(255, 255, 255, 0.9);
    }

    .play-overlay {
        position: absolute;
        bottom: 16px;
        right: 16px;
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
    }

    .artist-link {
        background: none;
        border: none;
        padding: 0;
        font: inherit;
        color: inherit;
        cursor: pointer;
    }

    .artist-link:hover {
        color: var(--text-primary);
        text-decoration: underline;
    }

    .subtitle {
        font-size: 11px;
        color: var(--text-secondary);
        opacity: 0.7;
        margin-top: 4px;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
    }

    @media (max-width: 600px) {
        .play-overlay {
            opacity: 1;
            transform: translateY(0);
        }
    }
</style>