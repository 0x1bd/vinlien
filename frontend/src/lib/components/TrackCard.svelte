<script lang="ts">
    import {goto} from '$app/navigation';
    import type {Track} from '$lib/utils/types';
    import {placeholderGradient} from '$lib/utils/artwork';

    export let track: Track;
    export let onPlay: () => void;

    let imgError = false;
    $: if (track) imgError = false;
</script>

<!-- svelte-ignore a11y-click-events-have-key-events -->
<!-- svelte-ignore a11y-no-static-element-interactions -->
<div class="track-card" on:click={onPlay}>
    <div class="img-wrapper">
        {#if track.artworkUrl && !imgError}
            <img src={track.artworkUrl} alt="art" loading="lazy" on:error={() => imgError = true}>
        {:else}
            <div class="artwork-placeholder" style="background: {placeholderGradient(track.artist + track.title)}">
                {track.title[0]?.toUpperCase() ?? '?'}
            </div>
        {/if}
    </div>
    <div class="info">
        <div class="title">{track.title}</div>
        <div class="artist">
            {#each track.artists as name, i}<!-- svelte-ignore a11y-click-events-have-key-events -->
                <!-- svelte-ignore a11y-no-static-element-interactions --><span class="artist-link"
                                                                                on:click|stopPropagation={() => goto(`/artist/${encodeURIComponent(name)}`)}>{name}</span>
                {#if i < track.artists.length - 1}{' & '}{/if}
            {/each}
        </div>
    </div>
    <button class="play-overlay">
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

    img {
        width: 100%;
        height: 100%;
        object-fit: cover;
        border-radius: 6px;
        box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
    }

    .artwork-placeholder {
        width: 100%;
        height: 100%;
        border-radius: 6px;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 48px;
        font-weight: 800;
        color: rgba(255, 255, 255, 0.9);
        box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
        text-shadow: 0 2px 8px rgba(0, 0, 0, 0.5);
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
        cursor: pointer;
    }

    .artist-link:hover {
        color: var(--text-primary);
        text-decoration: underline;
    }

    @media (max-width: 600px) {
        .play-overlay {
            opacity: 1;
            transform: translateY(0);
        }
    }
</style>