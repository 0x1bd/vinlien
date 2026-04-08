<script lang="ts">
    import {goto} from '$app/navigation';
    import {trackToAdd} from '$lib/utils/store';
    import type {Track} from '$lib/utils/types';

    export let track: Track;
    export let onPlay: () => void;

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
    <img src={track.artworkUrl} alt="art">
    <div class="info">
        <div class="title">{track.title}</div>
        <div class="artist">
            {#each track.artists as name, i}<!-- svelte-ignore a11y-click-events-have-key-events --><!-- svelte-ignore a11y-no-static-element-interactions --><span class="artist-link" on:click|stopPropagation={() => goto(`/artist/${encodeURIComponent(name)}`)}>{name}</span>{#if i < track.artists.length - 1}{' & '}{/if}{/each}
        </div>
    </div>
    {#if formatMs(track.durationMs)}
        <div class="duration">{formatMs(track.durationMs)}</div>
    {/if}
    <button class="add-btn" on:click|stopPropagation={() => $trackToAdd = track}>+</button>
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

    img {
        width: 40px;
        height: 40px;
        border-radius: 4px;
        object-fit: cover;
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

    @media (max-width: 768px) {
        .add-btn {
            opacity: 1;
        }
    }
</style>