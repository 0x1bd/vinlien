<script lang="ts">
    import {browser} from '$app/environment';
    import {
        currentTrack, isPlaying, isPlayerExpanded, repeatMode,
        volume, isMuted, showVolumeSlider, trackToAdd,
        showQueuePanel
    } from '$lib/utils/store';
    import {audioManager, audioProgress, currentTimeDisplay, durationDisplay} from '$lib/utils/AudioManager';
    import ArtworkImage from './ArtworkImage.svelte';
    import {goto} from '$app/navigation';
    import {onMount, tick} from 'svelte';

    export let isLiked: boolean;
    export let isDisliked: boolean;
    export let isDesktopExpanded: boolean;
    export let onToggleLike: () => void;
    export let onToggleDislike: () => void;
    export let onToggleInfo: () => void;
    export let showTrackInfo: boolean;

    let isDragging = false;
    let dragProgress = 0;
    let progressBarEl: HTMLElement;

    let titleViewportEl: HTMLDivElement;
    let titleTextEl: HTMLSpanElement;
    let isOverflowing = false;
    let shiftPx = 0;
    let durationSec = 8;
    const GAP = 26;

    $: displayProgress = isDragging ? dragProgress : $audioProgress;

    function pct(clientX: number) {
        if (!progressBarEl) return 0;
        const r = progressBarEl.getBoundingClientRect();
        return Math.max(0, Math.min(100, ((clientX - r.left) / r.width) * 100));
    }

    function onDown(e: PointerEvent) {
        e.stopPropagation();
        if (!$currentTrack) return;
        isDragging = true;
        dragProgress = pct(e.clientX);
        (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    }

    function onMove(e: PointerEvent) {
        if (isDragging) dragProgress = pct(e.clientX);
    }

    function onUp(e: PointerEvent) {
        if (!isDragging) return;
        isDragging = false;
        audioManager.seek(dragProgress);
    }

    function updateMarquee() {
        if (!browser || !$currentTrack) return;
        const vw = titleViewportEl?.clientWidth ?? 0;
        const tw = titleTextEl?.scrollWidth ?? 0;
        isOverflowing = tw - vw > 4;
        shiftPx = isOverflowing ? tw + GAP : 0;
        durationSec = Math.max(7, shiftPx / 28);
    }

    onMount(() => {
        const onResize = () => updateMarquee();
        window.addEventListener('resize', onResize);
        void tick().then(updateMarquee);
        return () => window.removeEventListener('resize', onResize);
    });

    $: if (browser) {
        $currentTrack;
        void tick().then(updateMarquee);
    }

    function handleBarClick(e: MouseEvent) {
        if (!$currentTrack) return;
        if ((e.target as Element).closest('button') ||
            (e.target as Element).closest('.progress-hitbox')) return;
        $isPlayerExpanded = !$isPlayerExpanded;
    }

    function providerLabel(id: string) {
        if (id.startsWith('lastfm:')) return 'Last.fm';
        if (id.startsWith('itunes:')) return 'iTunes';
        if (id.startsWith('sc:')) return 'SoundCloud';
        if (id.startsWith('ytmusic:')) return 'YouTube Music';
        return id.split(':')[0];
    }
</script>

<!-- svelte-ignore a11y-click-events-have-key-events -->
<!-- svelte-ignore a11y-no-noninteractive-element-interactions -->
<div class="mini-player" role="presentation" on:click={handleBarClick}>

    <!-- progress bar sits at very top of the bar -->
    <!-- svelte-ignore a11y-interactive-supports-focus -->
    <div class="progress-hitbox"
         bind:this={progressBarEl}
         on:pointerdown={onDown}
         on:pointermove={onMove}
         on:pointerup={onUp}
         on:pointercancel={onUp}
         role="slider"
         aria-label="Track progress"
         aria-valuemin="0"
         aria-valuemax="100"
         aria-valuenow={Math.round(displayProgress)}
         class:dragging={isDragging}>
        <div class="progress-bg">
            <div class="progress-bar" style="width: {displayProgress}%">
                <div class="progress-thumb"></div>
            </div>
        </div>
    </div>

    {#if $currentTrack}
        <div class="controls">

            <div class="data-group">
                <div class="artwork">
                    <ArtworkImage track={$currentTrack}>
                        {($currentTrack.title[0] ?? '?').toUpperCase()}
                    </ArtworkImage>
                </div>
                <div class="meta-col">
                    <div class="title">
                        <div class="marquee-vp" bind:this={titleViewportEl}>
                            <div class="marquee-track"
                                 class:overflowing={isOverflowing}
                                 style="--shift:{shiftPx}px;--dur:{durationSec}s">
                                <span class="marquee-copy" bind:this={titleTextEl}>{$currentTrack.title}</span>
                                <span class="marquee-sep" aria-hidden={!isOverflowing}>•</span>
                                <span class="marquee-copy marquee-clone"
                                      aria-hidden={!isOverflowing}>{$currentTrack.title}</span>
                            </div>
                        </div>
                    </div>
                    <div class="artist">
                        {#if $currentTrack.artists.length > 0}
                            {#each $currentTrack.artists as name, i}
                                <!-- svelte-ignore a11y-no-static-element-interactions -->
                                <span class="artist-link"
                                      on:click|stopPropagation={() => { $isPlayerExpanded = false; goto(`/artist/${encodeURIComponent(name)}`); }}>
                                    {name}
                                </span>
                                {#if i < $currentTrack.artists.length - 1}{' & '}{/if}
                            {/each}
                        {:else}
                            <span>{$currentTrack.artist}</span>
                        {/if}
                    </div>
                    {#if $currentTrack.albumTitle}
                        <div class="album">
                            <button class="album-btn"
                                    on:click|stopPropagation={() => { $isPlayerExpanded = false; goto(`/album/${encodeURIComponent($currentTrack.artist)}/${encodeURIComponent($currentTrack.albumTitle)}`); }}>
                                {$currentTrack.albumTitle}
                            </button>
                        </div>
                    {/if}
                </div>
                <div class="actions">
                    <button class="action-btn action-like" class:liked={isLiked}
                            on:click|stopPropagation={onToggleLike} title="Like">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill={isLiked ? 'currentColor' : 'none'}
                             stroke="currentColor" stroke-width="2">
                            <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"></path>
                        </svg>
                    </button>
                    <button class="action-btn action-dislike" class:disliked={isDisliked}
                            on:click|stopPropagation={onToggleDislike} title="Dislike">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill={isDisliked ? 'currentColor' : 'none'}
                             stroke="currentColor" stroke-width="2">
                            <path d="M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3zm7-13h2.67A2.31 2.31 0 0 1 22 4v7a2.31 2.31 0 0 1-2.33 2H17"></path>
                        </svg>
                    </button>
                    <button class="action-btn action-add"
                            on:click|stopPropagation={() => $trackToAdd = $currentTrack} title="Add to Playlist">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                             stroke-width="2">
                            <line x1="12" y1="5" x2="12" y2="19"></line>
                            <line x1="5" y1="12" x2="19" y2="12"></line>
                        </svg>
                    </button>
                </div>
            </div>

            <div class="transport-group">
                <button class="transport-btn prev-btn" on:click|stopPropagation={() => audioManager.prev()} aria-label="Previous track">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                        <path d="M6 6h2v12H6zm3.5 6l8.5 6V6z"/>
                    </svg>
                </button>
                <button class="play-btn" on:click|stopPropagation={() => $isPlaying = !$isPlaying}>
                    {#if $isPlaying}
                        <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
                            <path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/>
                        </svg>
                    {:else}
                        <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
                            <path d="M8 5v14l11-7z"/>
                        </svg>
                    {/if}
                </button>
                <button class="transport-btn next-btn" on:click|stopPropagation={() => audioManager.playNext(true)} aria-label="Next track">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                        <path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/>
                    </svg>
                </button>
            </div>

            <div class="right-group">
                <div class="time-display desktop-time">
                    <span>{$currentTimeDisplay}</span> / <span>{$durationDisplay}</span>
                </div>
                <button class="action-btn" class:active={$repeatMode > 0}
                        on:click|stopPropagation={() => $repeatMode = ($repeatMode + 1) % 3}>
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
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
                <button class="action-btn" on:click|stopPropagation={() => $isMuted = !$isMuted}>
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"></polygon>
                        {#if $isMuted}
                            <line x1="23" y1="9" x2="17" y2="15"></line>
                            <line x1="17" y1="9" x2="23" y2="15"></line>
                        {/if}
                    </svg>
                </button>
                {#if $showVolumeSlider}
                    <input type="range" class="volume-slider action-vol" min="0" max="1" step="0.001"
                           bind:value={$volume} on:click|stopPropagation/>
                {/if}
                {#if !isDesktopExpanded}
                    <button class="action-btn" class:active={$showQueuePanel}
                            on:click|stopPropagation={() => $showQueuePanel = !$showQueuePanel}
                            aria-label="Toggle queue">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                             stroke-width="2">
                            <line x1="8" y1="6" x2="21" y2="6"></line>
                            <line x1="8" y1="12" x2="21" y2="12"></line>
                            <line x1="8" y1="18" x2="21" y2="18"></line>
                        </svg>
                    </button>
                {/if}
                <button class="action-btn" class:active={showTrackInfo}
                        on:click|stopPropagation={onToggleInfo} title="Track info">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <circle cx="12" cy="12" r="10"></circle>
                        <line x1="12" y1="16" x2="12" y2="12"></line>
                        <line x1="12" y1="8" x2="12.01" y2="8"></line>
                    </svg>
                </button>
            </div>

        </div>
    {:else}
        <div class="idle-text">Pick a track to start listening</div>
    {/if}
</div>

<style>
    @keyframes marqueeLoop {
        0% {
            transform: translateX(0);
        }
        100% {
            transform: translateX(calc(-1 * var(--shift)));
        }
    }

    .mini-player {
        position: relative;
        height: 90px;
        padding: 0 24px;
        cursor: pointer;
        display: flex;
        align-items: stretch;
    }

    .progress-hitbox {
        position: absolute;
        top: -8px;
        left: 0;
        width: 100%;
        height: 16px;
        display: flex;
        align-items: center;
        cursor: pointer;
        z-index: 10;
    }

    .progress-hitbox.dragging {
        cursor: grabbing;
    }

    .progress-bg {
        width: 100%;
        height: 3px;
        background: var(--bg-hover);
        border-radius: 2px;
        position: relative;
    }

    .progress-bar {
        height: 100%;
        background: var(--text-primary);
        border-radius: 2px;
        position: relative;
    }

    .progress-thumb {
        position: absolute;
        right: -6px;
        top: 50%;
        transform: translateY(-50%);
        width: 12px;
        height: 12px;
        background: var(--text-primary);
        border-radius: 50%;
        opacity: 0;
        box-shadow: 0 2px 4px rgba(0, 0, 0, .5);
    }

    .progress-hitbox:hover .progress-bar,
    .progress-hitbox.dragging .progress-bar {
        background: var(--accent-color);
    }

    .progress-hitbox:hover .progress-thumb,
    .progress-hitbox.dragging .progress-thumb {
        opacity: 1;
    }

    .controls {
        display: flex;
        align-items: center;
        justify-content: space-between;
        width: 100%;
        height: 100%;
    }

    .data-group {
        display: flex;
        align-items: center;
        gap: 16px;
        width: 30%;
        min-width: 0;
    }

    .artwork {
        width: 56px;
        height: 56px;
        border-radius: 4px;
        flex-shrink: 0;
        font-size: 20px;
        font-weight: 800;
        color: var(--text-primary);
    }

    .meta-col {
        display: flex;
        flex-direction: column;
        gap: 4px;
        flex: 1;
        min-width: 0;
    }

    .title {
        font-weight: 600;
        font-size: 14px;
        white-space: nowrap;
        overflow: hidden;
    }

    .marquee-vp {
        min-width: 0;
        width: 100%;
        overflow: hidden;
    }

    .marquee-track {
        min-width: 0;
        width: 100%;
        white-space: nowrap;
    }

    .marquee-track.overflowing {
        display: inline-flex;
        align-items: center;
        width: max-content;
        max-width: none;
        animation: marqueeLoop var(--dur) linear infinite;
        will-change: transform;
    }

    .marquee-track:not(.overflowing) .marquee-sep,
    .marquee-track:not(.overflowing) .marquee-clone {
        display: none;
    }

    .marquee-sep {
        display: none;
        width: 26px;
        text-align: center;
        opacity: .6;
    }

    .marquee-track.overflowing .marquee-sep {
        display: inline-flex;
        align-items: center;
        justify-content: center;
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
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        display: inline-block;
        max-width: 100%;
    }

    .artist-link:hover {
        color: var(--text-primary);
        text-decoration: underline;
    }

    .album {
        font-size: 11px;
        color: var(--text-secondary);
        opacity: .7;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
    }

    .album-btn {
        background: none;
        border: none;
        padding: 0;
        font: inherit;
        font-size: 11px;
        color: inherit;
        cursor: pointer;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        display: inline-block;
        max-width: 100%;
    }

    .album-btn:hover {
        color: var(--text-primary);
        opacity: 1;
        text-decoration: underline;
    }

    .actions {
        display: flex;
        align-items: center;
        gap: 4px;
        flex-shrink: 0;
    }

    .transport-group {
        display: flex;
        gap: 24px;
        width: 40%;
        justify-content: center;
        align-items: center;
    }

    .transport-btn {
        background: transparent;
        border: none;
        padding: 8px;
        color: var(--text-secondary);
        cursor: pointer;
        display: flex;
    }

    .transport-btn:hover {
        color: var(--text-primary);
    }

    .play-btn {
        width: 32px;
        height: 32px;
        padding: 0;
        border: none;
        border-radius: 50%;
        background: var(--text-primary);
        color: var(--bg-base);
        display: flex;
        align-items: center;
        justify-content: center;
        cursor: pointer;
        transition: transform .15s;
    }

    .play-btn:hover {
        transform: scale(1.05);
    }

    .right-group {
        display: flex;
        align-items: center;
        justify-content: flex-end;
        gap: 8px;
        width: 30%;
    }

    .time-display {
        font-size: 12px;
        color: var(--text-secondary);
        margin-right: 16px;
        font-variant-numeric: tabular-nums;
        white-space: nowrap;
    }

    .volume-slider {
        width: 80px;
    }

    .idle-text {
        width: 100%;
        text-align: center;
        color: var(--text-secondary);
        font-size: 14px;
        line-height: 90px;
    }

    .action-btn {
        background: transparent;
        border: none;
        padding: 8px;
        color: var(--text-secondary);
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        cursor: pointer;
    }

    .action-btn:hover {
        color: var(--text-primary);
        transform: scale(1.05);
    }

    .action-btn.active, .action-btn.liked {
        color: var(--accent-color);
    }

    .action-btn.disliked {
        color: var(--danger-color);
    }

    @media (min-width: 601px) and (max-width: 1060px) {
        .time-display {
            display: none;
        }

        .volume-slider {
            display: none;
        }

        .right-group {
            gap: 4px;
        }
    }

    @media (min-width: 601px) and (max-width: 860px) {
        .right-group {
            display: none;
        }

        .transport-group {
            width: auto;
            flex: 0 0 auto;
        }

        .data-group {
            flex: 1;
            width: auto;
        }
    }

    @media (max-width: 600px) {
        .mini-player {
            height: 60px;
            padding: 0 12px;
        }

        .idle-text {
            line-height: 60px;
        }

        .progress-hitbox {
            top: auto;
            bottom: 0;
            left: 8px;
            width: calc(100% - 16px);
            height: 4px;
        }

        .progress-bg {
            height: 2px;
        }

        .progress-thumb {
            display: none;
        }

        .controls {
            flex-direction: row;
            flex-wrap: nowrap;
            gap: 8px;
        }

        .data-group {
            width: auto;
            flex: 1;
            gap: 10px;
            min-width: 0;
        }

        .artwork {
            width: 44px;
            height: 44px;
        }

        .meta-col {
            flex: 1;
            overflow: hidden;
            min-width: 0;
            gap: 2px;
        }

        .title {
            font-size: 13px;
        }

        .artist {
            font-size: 11px;
        }

        .album {
            display: none;
        }

        .actions .action-add {
            display: none !important;
        }

        .actions .action-like,
        .actions .action-dislike {
            padding: 6px;
        }

        .right-group {
            display: none;
        }

        .transport-group {
            width: auto;
            gap: 6px;
            justify-content: flex-end;
            align-items: center;
        }

        .prev-btn {
            display: none;
        }

        .next-btn {
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 6px;
        }

        .play-btn {
            width: 36px;
            height: 36px;
            background: transparent;
            color: var(--text-primary);
        }
    }
</style>
