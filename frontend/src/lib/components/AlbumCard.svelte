<script lang="ts">
    import {goto} from '$app/navigation';
    import type {Album} from '$lib/utils/types';

    export let album: Album;
</script>

<!-- svelte-ignore a11y-click-events-have-key-events -->
<!-- svelte-ignore a11y-no-static-element-interactions -->
<div class="album-card" on:click={() => goto(`/album/${encodeURIComponent(album.id)}`)}>
    <div class="img-wrapper">
        <img src={album.artworkUrl} alt="album art">
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