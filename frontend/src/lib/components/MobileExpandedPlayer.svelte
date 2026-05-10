<script lang="ts">
    import {
        currentTrack, isPlaying, isPlayerExpanded,
        repeatMode, trackToAdd, showQueuePanel
    } from '$lib/utils/store';
    import {audioManager, audioProgress, currentTimeDisplay, durationDisplay} from '$lib/utils/AudioManager';
    import ArtworkImage from './ArtworkImage.svelte';
    import {goto} from '$app/navigation';

    export let isLiked: boolean;
    export let isDisliked: boolean;
    export let onToggleLike: () => void;
    export let onToggleDislike: () => void;

    let touchStartY = 0;
    let isDragging = false;
    let dragProgress = 0;
    let progressBarEl: HTMLElement;

    $: displayProgress = isDragging ? dragProgress : $audioProgress;

    function pct(clientX: number): number {
        if (!progressBarEl) return 0;
        const r = progressBarEl.getBoundingClientRect();
        return Math.max(0, Math.min(100, ((clientX - r.left) / r.width) * 100));
    }

    function onPointerDown(e: PointerEvent) {
        e.stopPropagation();
        if (!$currentTrack) return;
        isDragging = true;
        dragProgress = pct(e.clientX);
        (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    }

    function onPointerMove(e: PointerEvent) {
        if (isDragging) dragProgress = pct(e.clientX);
    }

    function onPointerUp(e: PointerEvent) {
        if (!isDragging) return;
        isDragging = false;
        audioManager.seek(dragProgress);
    }

    function onTouchStart(e: TouchEvent) {
        touchStartY = e.touches[0].clientY;
    }

    function onTouchEnd(e: TouchEvent) {
        if (e.changedTouches[0].clientY - touchStartY > 80) $isPlayerExpanded = false;
    }

    function navArtist(name: string) {
        $isPlayerExpanded = false;
        goto(`/artist/${encodeURIComponent(name)}`);
    }

    function navAlbum() {
        if (!$currentTrack?.albumTitle) return;
        $isPlayerExpanded = false;
        goto(`/album/${encodeURIComponent($currentTrack.artist)}/${encodeURIComponent($currentTrack.albumTitle)}`);
    }
</script>

<!-- svelte-ignore a11y-no-static-element-interactions -->
<div class="mep" on:touchstart={onTouchStart} on:touchend={onTouchEnd}>

    <div class="mep-header">
        <button class="icon-btn" on:click={() => $isPlayerExpanded = false} aria-label="Collapse player">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="6 9 12 15 18 9"></polyline>
            </svg>
        </button>
        <span class="mep-now-playing">Now Playing</span>
        <div class="mep-header-spacer"></div>
    </div>

    {#if $currentTrack}
        <div class="mep-artwork">
            <ArtworkImage track={$currentTrack}>
                {($currentTrack.title[0] ?? '?').toUpperCase()}
            </ArtworkImage>
        </div>

        <div class="mep-meta">
            <div class="mep-title">{$currentTrack.title}</div>
            <div class="mep-artist">
                {#if $currentTrack.artists.length > 0}
                    {#each $currentTrack.artists as name, i}
                        <!-- svelte-ignore a11y-click-events-have-key-events -->
                        <span class="mep-artist-link" on:click={() => navArtist(name)}>{name}</span>
                        {#if i < $currentTrack.artists.length - 1}{' & '}{/if}
                    {/each}
                {:else}
                    <span>{$currentTrack.artist}</span>
                {/if}
            </div>
            {#if $currentTrack.albumTitle}
                <div class="mep-album">
                    <button class="mep-album-btn" on:click={navAlbum}>{$currentTrack.albumTitle}</button>
                </div>
            {/if}
        </div>

        <div class="mep-actions">
            <button class="mep-action-btn" class:liked={isLiked} on:click={onToggleLike} title="Like">
                <svg width="22" height="22" viewBox="0 0 24 24" fill={isLiked ? 'currentColor' : 'none'}
                     stroke="currentColor" stroke-width="2">
                    <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"></path>
                </svg>
            </button>
            <button class="mep-action-btn" class:disliked={isDisliked} on:click={onToggleDislike} title="Dislike">
                <svg width="22" height="22" viewBox="0 0 24 24" fill={isDisliked ? 'currentColor' : 'none'}
                     stroke="currentColor" stroke-width="2">
                    <path d="M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3zm7-13h2.67A2.31 2.31 0 0 1 22 4v7a2.31 2.31 0 0 1-2.33 2H17"></path>
                </svg>
            </button>
            <button class="mep-action-btn" on:click={() => $trackToAdd = $currentTrack} title="Add to Playlist">
                <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <line x1="12" y1="5" x2="12" y2="19"></line>
                    <line x1="5" y1="12" x2="19" y2="12"></line>
                </svg>
            </button>
            <button class="mep-action-btn" class:active={$repeatMode > 0}
                    on:click={() => $repeatMode = ($repeatMode + 1) % 3} title="Repeat">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline points="17 1 21 5 17 9"></polyline>
                    <path d="M3 11V9a4 4 0 0 1 4-4h14"></path>
                    <polyline points="7 23 3 19 7 15"></polyline>
                    <path d="M21 13v2a4 4 0 0 1-4 4H3"></path>
                    {#if $repeatMode === 2}
                        <text x="12" y="15" font-size="9" font-weight="bold" text-anchor="middle"
                              font-family="sans-serif" stroke="none" fill="currentColor">1
                        </text>
                    {/if}
                </svg>
            </button>
            <button class="mep-action-btn" class:active={$showQueuePanel}
                    on:click={() => $showQueuePanel = !$showQueuePanel} title="Queue">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <line x1="8" y1="6" x2="21" y2="6"></line>
                    <line x1="8" y1="12" x2="21" y2="12"></line>
                    <line x1="8" y1="18" x2="21" y2="18"></line>
                </svg>
            </button>
        </div>

        <div class="mep-progress">
            <!-- svelte-ignore a11y-interactive-supports-focus -->
            <div class="mep-progress-hitbox"
                 bind:this={progressBarEl}
                 on:pointerdown={onPointerDown}
                 on:pointermove={onPointerMove}
                 on:pointerup={onPointerUp}
                 on:pointercancel={onPointerUp}
                 role="slider"
                 aria-label="Track progress"
                 aria-valuemin="0"
                 aria-valuemax="100"
                 aria-valuenow={Math.round(displayProgress)}
                 class:dragging={isDragging}>
                <div class="mep-progress-bg">
                    <div class="mep-progress-fill" style="width: {displayProgress}%">
                        <div class="mep-progress-thumb"></div>
                    </div>
                </div>
            </div>
            <div class="mep-time-row">
                <span>{$currentTimeDisplay}</span>
                <span>{$durationDisplay}</span>
            </div>
        </div>

        <div class="mep-transport">
            <button class="mep-transport-btn" on:click={() => audioManager.prev()} aria-label="Previous">
                <svg width="32" height="32" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M6 6h2v12H6zm3.5 6l8.5 6V6z"/>
                </svg>
            </button>
            <button class="mep-play-btn" on:click={() => $isPlaying = !$isPlaying}
                    aria-label={$isPlaying ? 'Pause' : 'Play'}>
                {#if $isPlaying}
                    <svg width="36" height="36" viewBox="0 0 24 24" fill="currentColor">
                        <path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/>
                    </svg>
                {:else}
                    <svg width="36" height="36" viewBox="0 0 24 24" fill="currentColor">
                        <path d="M8 5v14l11-7z"/>
                    </svg>
                {/if}
            </button>
            <button class="mep-transport-btn" on:click={() => audioManager.playNext(true)} aria-label="Next">
                <svg width="32" height="32" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/>
                </svg>
            </button>
        </div>
    {/if}
</div>

<style>
    .mep {
        position: fixed;
        inset: 0;
        z-index: 500;
        background: var(--bg-surface);
        display: flex;
        flex-direction: column;
        padding: env(safe-area-inset-top, 0px) 22px calc(32px + env(safe-area-inset-bottom, 0px));
        overflow: hidden;
    }

    .mep-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        height: 64px;
        flex-shrink: 0;
    }

    .mep-now-playing {
        font-size: 12px;
        font-weight: 700;
        letter-spacing: 0.1em;
        text-transform: uppercase;
        color: var(--text-secondary);
    }

    .mep-header-spacer {
        width: 40px;
    }

    .mep-artwork {
        flex: 1;
        min-height: 0;
        display: flex;
        align-items: center;
        justify-content: center;
        padding: 8px 0;
    }

    .mep-artwork :global(.artwork-image),
    .mep-artwork :global(.artwork-placeholder) {
        width: min(320px, 100%);
        aspect-ratio: 1;
        border-radius: 20px;
        box-shadow: 0 16px 48px rgba(0, 0, 0, 0.5);
    }

    .mep-meta {
        flex-shrink: 0;
        margin-top: 16px;
        display: flex;
        flex-direction: column;
        gap: 4px;
    }

    .mep-title {
        font-size: 22px;
        font-weight: 800;
        line-height: 1.2;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
    }

    .mep-artist {
        font-size: 15px;
        color: var(--text-secondary);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
    }

    .mep-artist-link {
        cursor: pointer;
    }

    .mep-artist-link:hover {
        color: var(--text-primary);
        text-decoration: underline;
    }

    .mep-album {
        margin-top: 2px;
    }

    .mep-album-btn {
        background: none;
        border: none;
        padding: 0;
        font: inherit;
        font-size: 13px;
        color: var(--text-secondary);
        opacity: 0.75;
        cursor: pointer;
    }

    .mep-album-btn:hover {
        opacity: 1;
        text-decoration: underline;
    }

    .mep-actions {
        display: flex;
        align-items: center;
        gap: 4px;
        margin-top: 16px;
        flex-shrink: 0;
    }

    .mep-action-btn {
        background: transparent;
        border: none;
        padding: 10px;
        color: var(--text-secondary);
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        cursor: pointer;
        transition: color 0.15s, transform 0.15s;
    }

    .mep-action-btn:hover {
        color: var(--text-primary);
        transform: scale(1.1);
    }

    .mep-action-btn.liked {
        color: var(--accent-color);
    }

    .mep-action-btn.disliked {
        color: var(--danger-color);
    }

    .mep-action-btn.active {
        color: var(--accent-color);
    }

    .mep-progress {
        margin-top: 20px;
        flex-shrink: 0;
    }

    .mep-progress-hitbox {
        width: 100%;
        height: 20px;
        display: flex;
        align-items: center;
        cursor: pointer;
        touch-action: none;
    }

    .mep-progress-hitbox.dragging {
        cursor: grabbing;
    }

    .mep-progress-bg {
        width: 100%;
        height: 4px;
        background: var(--bg-hover);
        border-radius: 2px;
        position: relative;
    }

    .mep-progress-fill {
        height: 100%;
        background: var(--text-primary);
        border-radius: 2px;
        position: relative;
    }

    .mep-progress-hitbox:hover .mep-progress-fill,
    .mep-progress-hitbox.dragging .mep-progress-fill {
        background: var(--accent-color);
    }

    .mep-progress-thumb {
        position: absolute;
        right: -6px;
        top: 50%;
        transform: translateY(-50%);
        width: 14px;
        height: 14px;
        background: var(--text-primary);
        border-radius: 50%;
        box-shadow: 0 2px 4px rgba(0, 0, 0, 0.5);
        opacity: 0;
        transition: opacity 0.15s;
    }

    .mep-progress-hitbox:hover .mep-progress-thumb,
    .mep-progress-hitbox.dragging .mep-progress-thumb {
        opacity: 1;
    }

    .mep-time-row {
        display: flex;
        justify-content: space-between;
        font-size: 12px;
        color: var(--text-secondary);
        font-variant-numeric: tabular-nums;
        margin-top: 4px;
    }

    .mep-transport {
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 24px;
        margin-top: 20px;
        flex-shrink: 0;
    }

    .mep-transport-btn {
        background: transparent;
        border: none;
        padding: 8px;
        color: var(--text-secondary);
        cursor: pointer;
        border-radius: 50%;
        display: flex;
        transition: color 0.15s, transform 0.15s;
    }

    .mep-transport-btn:hover {
        color: var(--text-primary);
        transform: scale(1.08);
    }

    .mep-play-btn {
        width: 72px;
        height: 72px;
        border-radius: 50%;
        border: none;
        background: var(--text-primary);
        color: var(--bg-base);
        display: flex;
        align-items: center;
        justify-content: center;
        cursor: pointer;
        transition: transform 0.15s;
        flex-shrink: 0;
    }

    .mep-play-btn:hover {
        transform: scale(1.05);
    }

    .icon-btn {
        background: transparent;
        border: none;
        color: var(--text-secondary);
        padding: 8px;
        display: flex;
        align-items: center;
        justify-content: center;
        cursor: pointer;
        border-radius: 50%;
        transition: color 0.15s;
    }

    .icon-btn:hover {
        color: var(--text-primary);
    }
</style>
