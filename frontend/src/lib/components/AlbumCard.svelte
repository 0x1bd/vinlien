<script lang="ts">
    import type {Album} from '$lib/utils/types';
    import ArtworkImage from './ArtworkImage.svelte';

    export let album: Album;
</script>

<a class="album-card" href="/album/{encodeURIComponent(album.id)}">
    <div class="img-wrapper">
        <ArtworkImage src={album.artworkUrl} seed={album.artist + album.title}>
            {album.title[0]?.toUpperCase() ?? '?'}
        </ArtworkImage>
        <div class="year-badge">{album.year || ''}</div>
    </div>
    <div class="info">
        <div class="title">{album.title}</div>
        <div class="artist">{album.artist}</div>
    </div>
</a>

<style>
    .album-card {
        display: block;
        text-decoration: none;
        color: inherit;
        background: var(--bg-elevated);
        padding: 16px;
        border-radius: 8px;
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
        border-radius: 6px;
        box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
        font-size: 48px;
        font-weight: 800;
        color: rgba(255, 255, 255, 0.9);
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
        z-index: 1;
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
