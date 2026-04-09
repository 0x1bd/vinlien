<script lang="ts">
    import {goto} from '$app/navigation';
    import type {Album} from '$lib/utils/types';

    export let album: Album;

    function placeholderGradient(seed: string): string {
        let h = 0;
        for (let i = 0; i < seed.length; i++) h = (Math.imul(31, h) + seed.charCodeAt(i)) | 0;
        const hue1 = ((h >>> 0) % 360);
        const hue2 = (hue1 + 40 + ((h >>> 8) % 80)) % 360;
        const angle = (h >>> 16) % 360;
        return `linear-gradient(${angle}deg, hsl(${hue1},60%,30%), hsl(${hue2},70%,50%))`;
    }
</script>

<!-- svelte-ignore a11y-click-events-have-key-events -->
<!-- svelte-ignore a11y-no-static-element-interactions -->
<div class="album-card" on:click={() => goto(`/album/${encodeURIComponent(album.id)}`)}>
    <div class="img-wrapper">
        {#if album.artworkUrl}
            <img src={album.artworkUrl} alt="album art">
        {:else}
            <div class="artwork-placeholder" style="background: {placeholderGradient(album.artist + album.title)}">
                {album.title[0]?.toUpperCase() ?? '?'}
            </div>
        {/if}
        <div class="year-badge">{album.year || ''}</div>
    </div>
    <div class="info">
        <div class="title">{album.title}</div>
        <div class="artist">{album.artist}</div>
    </div>
</div>

<style>
    .album-card {
        background: var(--bg-elevated);
        padding: 16px;
        border-radius: 8px;
        cursor: pointer;
        transition: 0.3s;
    }

    .album-card:hover {
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

    .year-badge {
        position: absolute;
        top: 8px;
        left: 8px;
        background: rgba(0, 0, 0, 0.7);
        padding: 4px 8px;
        border-radius: 4px;
        font-size: 11px;
        font-weight: 600;
        backdrop-filter: blur(4px);
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
</style>