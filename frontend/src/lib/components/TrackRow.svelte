<script lang="ts">
    import {goto} from '$app/navigation';
    import {trackToAdd} from '$lib/utils/store';
    import type {Track} from '$lib/utils/types';
    import ArtworkImage from './ArtworkImage.svelte';

    export let track: Track;
    export let onPlay: () => void;
    export let onDelete: (() => void) | null = null;

    function formatMs(ms: number) {
        if (!ms || ms === 0) return "";
        const m = Math.floor(ms / 60000);
        const s = Math.floor((ms % 60000) / 1000).toString().padStart(2, '0');
        return `${m}:${s}`;
    }
</script>

<!-- svelte-ignore a11y-click-events-have-key-events -->
<!-- svelte-ignore a11y-no-static-element-interactions -->
<div class="track-row" on:click={onPlay}>
    <div class="artwork">
        <ArtworkImage src={track.artworkUrl} seed={track.artist + track.title}/>
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
    {#if formatMs(track.durationMs)}
        <div class="duration">{formatMs(track.durationMs)}</div>
    {/if}
    <button class="add-btn" on:click|stopPropagation={() => $trackToAdd = track}>+</button>
    {#if onDelete}
        <button class="delete-btn" on:click|stopPropagation={onDelete} title="Remove from playlist">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="3 6 5 6 21 6"></polyline>
                <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"></path>
                <path d="M10 11v6M14 11v6"></path>
                <path d="M9 6V4h6v2"></path>
            </svg>
        </button>
    {/if}
</div>

<style>
    .track-row {
        display: flex;
        align-items: center;
        gap: 16px;
        padding: 8px 16px;
        border-radius: 6px;
        cursor: pointer;
        transition: 0.2s;
    }

    .track-row:hover {
        background: var(--bg-hover);
    }

    .artwork {
        width: 40px;
        height: 40px;
        border-radius: 4px;
        flex-shrink: 0;
    }

    .info {
        flex: 1;
        min-width: 0;
    }

    .title {
        font-size: 14px;
        font-weight: 500;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        color: var(--text-primary);
    }

    .artist {
        font-size: 12px;
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

    .duration {
        font-size: 13px;
        color: var(--text-secondary);
        font-variant-numeric: tabular-nums;
    }

    .add-btn {
        background: transparent;
        color: var(--text-secondary);
        padding: 8px;
        opacity: 0;
        transition: 0.2s;
    }

    .track-row:hover .add-btn {
        opacity: 1;
    }

    .add-btn:hover {
        color: var(--text-primary);
        transform: scale(1.1);
    }

    .delete-btn {
        background: transparent;
        color: var(--text-secondary);
        padding: 8px;
        opacity: 0;
        transition: 0.2s;
        display: flex;
        align-items: center;
        justify-content: center;
    }

    .track-row:hover .delete-btn {
        opacity: 1;
    }

    .delete-btn:hover {
        color: var(--danger-color, #ef4444);
        transform: scale(1.1);
    }

    @media (max-width: 768px) {
        .add-btn, .delete-btn {
            opacity: 1;
        }
    }
</style>