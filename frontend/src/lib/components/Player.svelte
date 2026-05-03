<script lang="ts">
    import {browser} from '$app/environment';
    import {
        currentTrack,
        isPlaying,
        userPlaylists,
        repeatMode,
        volume,
        isMuted,
        showVolumeSlider,
        trackToAdd,
        showQueuePanel,
        autoDownloadPlaylists,
        requireOnline,
        fetchSimilarTracksIfNeeded,
        isPlayerExpanded
    } from '$lib/utils/store';
    import {get} from 'svelte/store';
    import {onMount, tick} from 'svelte';
    import {downloadTrack} from '$lib/utils/offlineAudio';
    import {apiRequest} from '$lib/utils/api';
    import {addToast} from '$lib/utils/toast';
    import {audioManager, audioProgress, currentTimeDisplay, durationDisplay} from '$lib/utils/AudioManager';
    import QueuePanel from './QueuePanel.svelte';
    import {goto} from '$app/navigation';
    import {page} from '$app/state';
    import ArtworkImage from './ArtworkImage.svelte';
    import DesktopExpandedPlayer from './DesktopExpandedPlayer.svelte';

    $: likedPlaylist = $userPlaylists.find(p => p.name === 'Liked Songs');
    $: dislikedPlaylist = $userPlaylists.find(p => p.name === 'Disliked Songs');
    $: isLiked = $currentTrack && likedPlaylist?.tracks.some(t => t.id === $currentTrack.id);
    $: isDisliked = $currentTrack && dislikedPlaylist?.tracks.some(t => t.id === $currentTrack.id);



    let touchStartY = 0;
    let showTrackInfo = false;
    let innerWidth = 0;

    let isDraggingProgress = false;
    let dragProgress = 0;
    let progressBarEl: HTMLElement;
    let mobileTitleViewportEl: HTMLDivElement;
    let mobileTitleTextEl: HTMLSpanElement;
    let isMobileTitleOverflowing = false;
    let mobileTitleShiftPx = 0;
    let mobileTitleDurationSec = 8;
    let lastPathname = '';
    const marqueeGapPx = 26;

    $: displayProgress = isDraggingProgress ? dragProgress : $audioProgress;

    function updateMobileOverflowState() {
        if (!browser || !$currentTrack) return;
        if ($isPlayerExpanded) {
            isMobileTitleOverflowing = false;
            mobileTitleShiftPx = 0;
            return;
        }
        const titleViewportWidth = mobileTitleViewportEl?.clientWidth ?? 0;
        const titleTextWidth = mobileTitleTextEl?.scrollWidth ?? 0;
        isMobileTitleOverflowing = titleTextWidth - titleViewportWidth > 4;
        mobileTitleShiftPx = isMobileTitleOverflowing ? titleTextWidth + marqueeGapPx : 0;
        mobileTitleDurationSec = Math.max(7, mobileTitleShiftPx / 28);
    }

    onMount(() => {
        const onResize = () => updateMobileOverflowState();
        window.addEventListener('resize', onResize);
        void tick().then(updateMobileOverflowState);
        return () => window.removeEventListener('resize', onResize);
    });

    $: if (browser) {
        $currentTrack;
        $isPlayerExpanded;
        void tick().then(updateMobileOverflowState);
    }

    $: if (!$currentTrack && $isPlayerExpanded) {
        $isPlayerExpanded = false;
    }

    $: if (browser) {
        const pathname = page.url.pathname;
        if (lastPathname && pathname !== lastPathname && $isPlayerExpanded) {
            $isPlayerExpanded = false;
        }
        lastPathname = pathname;
    }

    $: if ($isPlayerExpanded && $currentTrack) {
        fetchSimilarTracksIfNeeded($currentTrack);
    }

    function progressFromPointer(clientX: number): number {
        if (!progressBarEl) return 0;
        const rect = progressBarEl.getBoundingClientRect();
        return Math.max(0, Math.min(100, ((clientX - rect.left) / rect.width) * 100));
    }

    function handleProgressPointerDown(e: PointerEvent) {
        e.stopPropagation();
        if (!$currentTrack) return;
        isDraggingProgress = true;
        dragProgress = progressFromPointer(e.clientX);
        (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    }

    function handleProgressPointerMove(e: PointerEvent) {
        if (!isDraggingProgress) return;
        dragProgress = progressFromPointer(e.clientX);
    }

    function handleProgressPointerUp(e: PointerEvent) {
        if (!isDraggingProgress) return;
        isDraggingProgress = false;
        audioManager.seek(dragProgress);
    }

    function providerLabel(id: string): string {
        if (id.startsWith('lastfm:')) return 'Last.fm';
        if (id.startsWith('itunes:')) return 'iTunes';
        if (id.startsWith('sc:')) return 'SoundCloud';
        if (id.startsWith('invidious:')) return 'Invidious';
        return id.split(':')[0];
    }

    function togglePlay() {
        $isPlaying = !$isPlaying;
    }

    function handleTouchStart(e: TouchEvent) {
        if ($isPlayerExpanded) {
            touchStartY = e.touches[0].clientY;
        }
    }

    function handleTouchEnd(e: TouchEvent) {
        if ($isPlayerExpanded) {
            const touchEndY = e.changedTouches[0].clientY;
            if (touchEndY - touchStartY > 80) {
                $isPlayerExpanded = false;
            }
        }
    }

    function handlePlayerBarClick(e: MouseEvent) {
        if (!$currentTrack) return;

        if ((e.target as Element).closest('button') ||
            (e.target as Element).closest('.progress-hitbox') ||
            (e.target as Element).closest('.expanded-header') ||
            (e.target as Element).closest('.track-info-panel') ||
            (e.target as Element).closest('.actions')) {
            return;
        }

        if (!$isPlayerExpanded || innerWidth > 600) {
            $isPlayerExpanded = !$isPlayerExpanded;
        }
    }

    async function toggleLike() {
        if (!$currentTrack) return;
        if (!requireOnline('Cannot like songs while offline')) return;
        const prevLiked = isLiked;
        isLiked = !isLiked;
        if (isLiked) isDisliked = false;
        addToast(isLiked ? 'Added to Liked Songs' : 'Removed from Liked Songs', 'success');
        try {
            await apiRequest('/api/playlists/liked/toggle', {method: 'POST', body: $currentTrack});
            const res = await apiRequest('/api/playlists');
            if (res) $userPlaylists = res;
            if (isLiked) {
                const likedId = $userPlaylists.find(p => p.name === 'Liked Songs')?.id;
                if (likedId && get(autoDownloadPlaylists).includes(likedId)) {
                    downloadTrack($currentTrack).catch(() => {});
                }
            }
        } catch (e) {
            isLiked = prevLiked;
        }
    }

    async function toggleDislike() {
        if (!$currentTrack) return;
        if (!requireOnline('Cannot dislike songs while offline')) return;
        const prevDisliked = isDisliked;
        isDisliked = !isDisliked;
        if (isDisliked) isLiked = false;
        addToast(isDisliked ? 'Will not recommend this song' : 'Removed from Dislikes', 'info');
        try {
            await apiRequest('/api/playlists/disliked/toggle', {method: 'POST', body: $currentTrack});
            const res = await apiRequest('/api/playlists');
            if (res) $userPlaylists = res;
            if (isDisliked) {
                const dislikedId = $userPlaylists.find(p => p.name === 'Disliked Songs')?.id;
                if (dislikedId && get(autoDownloadPlaylists).includes(dislikedId)) {
                    downloadTrack($currentTrack).catch(() => {});
                }
                if ($isPlaying) await audioManager.playNext(true);
            }
        } catch (e) {
            isDisliked = prevDisliked;
        }
    }
</script>

<svelte:window bind:innerWidth />

{#if !$isPlayerExpanded}
    <QueuePanel/>
{/if}

{#if $isPlayerExpanded && innerWidth > 600}
    <DesktopExpandedPlayer />
{/if}

<div class="player-wrapper" class:expanded={$isPlayerExpanded && innerWidth <= 600}>

    <div class="player-layout">
        <!-- svelte-ignore a11y-click-events-have-key-events -->
        <!-- svelte-ignore a11y-no-noninteractive-element-interactions -->
        <div class="player-bar"
             on:click={handlePlayerBarClick}
             on:touchstart={handleTouchStart}
             on:touchend={handleTouchEnd}
             role="presentation"
        >
            {#if $currentTrack}
                {#if $isPlayerExpanded}
                    <div class="expanded-header">
                        <button class="icon-btn collapse-btn" on:click|stopPropagation={() => $isPlayerExpanded = false}>
                            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                                 stroke-width="2">
                                <polyline points="6 9 12 15 18 9"></polyline>
                            </svg>
                        </button>
                        <span>Now Playing</span>
                        <div style="width: 40px"></div>
                    </div>
                {/if}

                <div class="controls-area">
                    <div class="data-group">
                        <div class="artwork">
                            <ArtworkImage src={$currentTrack.artworkUrl} seed={$currentTrack.artist + $currentTrack.title}>
                                {($currentTrack.title[0] ?? '?').toUpperCase()}
                            </ArtworkImage>
                        </div>
                        <div class="data-row">
                            <div class="metadata">
                                <div class="title">
                                    <div class="mobile-marquee-viewport" bind:this={mobileTitleViewportEl}>
                                        <div class="mobile-marquee-track"
                                             class:overflowing={isMobileTitleOverflowing}
                                             style={`--marquee-shift: ${mobileTitleShiftPx}px; --marquee-duration: ${mobileTitleDurationSec}s;`}>
                                            <span class="mobile-marquee-copy" bind:this={mobileTitleTextEl}>{$currentTrack.title}</span>
                                            <span class="mobile-marquee-sep" aria-hidden={!isMobileTitleOverflowing}>•</span>
                                            <span class="mobile-marquee-copy mobile-marquee-copy-clone" aria-hidden={!isMobileTitleOverflowing}>{$currentTrack.title}</span>
                                        </div>
                                    </div>
                                </div>
                                <div class="artist">
                                    {#if $currentTrack.artists.length > 0}
                                      {#each $currentTrack.artists as name, i}<!-- svelte-ignore a11y-click-events-have-key-events --><!-- svelte-ignore a11y-no-static-element-interactions --><span class="artist-link" on:click|stopPropagation={() => { $isPlayerExpanded = false; goto(`/artist/${encodeURIComponent(name)}`); }}>{name}</span>{#if i < $currentTrack.artists.length - 1}{' & '}{/if}{/each}
                                {:else}
                                    <span>{$currentTrack.artist}</span>
                                {/if}
                            </div>
                            {#if $currentTrack.albumTitle}
                                <div class="album">
                                    {#if $currentTrack.albumTitle}
                                        <button class="album-link" on:click|stopPropagation={() => { $isPlayerExpanded = false; goto(`/album/${encodeURIComponent($currentTrack.artist)}/${encodeURIComponent($currentTrack.albumTitle)}`); }}>{$currentTrack.albumTitle}</button>
                                        {:else}
                                            <span>{$currentTrack.albumTitle}</span>
                                        {/if}
                                    </div>
                                {/if}
                            </div>
                            <div class="actions">
                                <button class="action-btn action-like" class:liked={isLiked}
                                        on:click|stopPropagation={toggleLike}
                                        title="Like">
                                    <svg width="18" height="18" viewBox="0 0 24 24" fill={isLiked ? "currentColor" : "none"}
                                         stroke="currentColor" stroke-width="2">
                                        <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"></path>
                                    </svg>
                                </button>
                                <button class="action-btn action-dislike" class:disliked={isDisliked}
                                        on:click|stopPropagation={toggleDislike} title="Dislike (Skip & Don't Recommend)">
                                    <svg width="18" height="18" viewBox="0 0 24 24"
                                         fill={isDisliked ? "currentColor" : "none"}
                                         stroke="currentColor" stroke-width="2">
                                        <path d="M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3zm7-13h2.67A2.31 2.31 0 0 1 22 4v7a2.31 2.31 0 0 1-2.33 2H17"></path>
                                    </svg>
                                </button>
                                <button class="action-btn action-add"
                                        on:click|stopPropagation={() => { $trackToAdd = $currentTrack; }}
                                        title="Add to Playlist">
                                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                                         stroke-width="2">
                                        <line x1="12" y1="5" x2="12" y2="19"></line>
                                        <line x1="5" y1="12" x2="19" y2="12"></line>
                                    </svg>
                                </button>
                            </div>
                        </div>
                    </div>

                    <!-- svelte-ignore a11y-click-events-have-key-events -->
                    <!-- svelte-ignore a11y-interactive-supports-focus -->
                    <div class="progress-hitbox"
                         bind:this={progressBarEl}
                         on:pointerdown={handleProgressPointerDown}
                         on:pointermove={handleProgressPointerMove}
                         on:pointerup={handleProgressPointerUp}
                         on:pointercancel={handleProgressPointerUp}
                         role="slider"
                         class:dragging={isDraggingProgress}>
                        <div class="progress-bg">
                            <div class="progress-bar" style="width: {displayProgress}%">
                                <div class="progress-thumb"></div>
                            </div>
                        </div>
                    </div>

                    {#if $isPlayerExpanded}
                        <div class="expanded-time">
                            <span>{$currentTimeDisplay}</span>
                            <span>{$durationDisplay}</span>
                        </div>
                    {/if}

                    <div class="transport-group">
                        <button class="transport-btn prev-btn" on:click|stopPropagation={() => audioManager.prev()}>
                            <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                                <path d="M6 6h2v12H6zm3.5 6l8.5 6V6z"/>
                            </svg>
                        </button>
                        <button class="play-btn" on:click|stopPropagation={togglePlay}>
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
                        <button class="transport-btn next-btn" on:click|stopPropagation={() => audioManager.playNext(true)}>
                            <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                                <path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/>
                            </svg>
                        </button>
                    </div>

                    <div class="right-group">
                        <div class="time-display desktop-time"><span>{$currentTimeDisplay}</span> /
                            <span>{$durationDisplay}</span></div>

                        <button class="action-btn action-repeat" class:active={$repeatMode > 0}
                                on:click|stopPropagation={() => $repeatMode = ($repeatMode + 1) % 3}>
                            {#if $repeatMode === 2}
                                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                                     stroke-width="2">
                                    <polyline points="17 1 21 5 17 9"></polyline>
                                    <path d="M3 11V9a4 4 0 0 1 4-4h14"></path>
                                    <polyline points="7 23 3 19 7 15"></polyline>
                                    <path d="M21 13v2a4 4 0 0 1-4 4H3"></path>
                                    <text x="12" y="15" font-size="9" font-weight="bold" text-anchor="middle"
                                          font-family="sans-serif" stroke="none" fill="currentColor">1
                                    </text>
                                </svg>
                            {:else}
                                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                                     stroke-width="2">
                                    <polyline points="17 1 21 5 17 9"></polyline>
                                    <path d="M3 11V9a4 4 0 0 1 4-4h14"></path>
                                    <polyline points="7 23 3 19 7 15"></polyline>
                                    <path d="M21 13v2a4 4 0 0 1-4 4H3"></path>
                                </svg>
                            {/if}
                        </button>

                        <button class="action-btn action-mute" on:click|stopPropagation={() => $isMuted = !$isMuted}>
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                                 stroke-width="2">
                                <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"></polygon>
                                {#if $isMuted}
                                    <line x1="23" y1="9" x2="17" y2="15"></line>
                                    <line x1="17" y1="9" x2="23" y2="15"></line>
                                {/if}
                            </svg>
                        </button>
                        {#if $showVolumeSlider}
                            <input type="range" class="volume-slider action-vol" min="0" max="1" step="0.01"
                                   bind:value={$volume} on:click|stopPropagation/>
                        {/if}
                        {#if !($isPlayerExpanded && innerWidth > 600)}
                            <button class="action-btn action-queue" class:active={$showQueuePanel}
                                    on:click|stopPropagation={() => { $showQueuePanel = !$showQueuePanel; }}>
                                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                                     stroke-width="2">
                                    <line x1="8" y1="6" x2="21" y2="6"></line>
                                    <line x1="8" y1="12" x2="21" y2="12"></line>
                                    <line x1="8" y1="18" x2="21" y2="18"></line>
                                </svg>
                            </button>
                        {/if}
                        <button class="action-btn action-info" class:active={showTrackInfo}
                                on:click|stopPropagation={() => { showTrackInfo = !showTrackInfo; $showQueuePanel = false; }}
                                title="Track info">
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                                 stroke-width="2">
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
        {#if $isPlayerExpanded && innerWidth <= 600}
            <div class="expanded-queue-pane">
                <QueuePanel inline={true} />
            </div>
        {/if}
    </div>
</div>

{#if showTrackInfo && $currentTrack}
    <!-- svelte-ignore a11y-click-events-have-key-events -->
    <!-- svelte-ignore a11y-no-static-element-interactions -->
    <div class="track-info-panel" on:click|stopPropagation>
        <div class="info-header">
            <span class="info-title">Track Info</span>
            <span class="provider-badge">{providerLabel($currentTrack.id)}</span>
            <button class="icon-btn info-close" on:click|stopPropagation={() => showTrackInfo = false}>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <line x1="18" y1="6" x2="6" y2="18"></line>
                    <line x1="6" y1="6" x2="18" y2="18"></line>
                </svg>
            </button>
        </div>
        <div class="info-rows">
            <div class="info-row">
                <span class="info-label">Title</span>
                <span class="info-value">{$currentTrack.title}</span>
            </div>
            <div class="info-row">
                <span class="info-label">Artist</span>
                <span class="info-value">{$currentTrack.artist}</span>
            </div>
            <div class="info-row">
                <span class="info-label">Track ID</span>
                <span class="info-value mono">{$currentTrack.id}</span>
            </div>
            {#if $currentTrack.canonicalId}
                <div class="info-row">
                    <span class="info-label">Canonical ID</span>
                    <span class="info-value mono">{$currentTrack.canonicalId}</span>
                </div>
            {/if}
            {#if $currentTrack.durationMs}
                <div class="info-row">
                    <span class="info-label">Duration</span>
                    <span class="info-value">{Math.floor($currentTrack.durationMs / 60000)}:{String(Math.floor(($currentTrack.durationMs % 60000) / 1000)).padStart(2, '0')}</span>
                </div>
            {/if}
            {#if $currentTrack.lastFmUrl}
                <div class="info-row">
                    <span class="info-label">Last.fm</span>
                    <a class="info-value info-link" href={$currentTrack.lastFmUrl} target="_blank" rel="noopener noreferrer"
                       on:click|stopPropagation>{$currentTrack.lastFmUrl}</a>
                </div>
            {/if}
            {#if $currentTrack.streamUrl}
                <div class="info-row">
                    <span class="info-label">Stream</span>
                    <a class="info-value info-link" href={$currentTrack.streamUrl} target="_blank" rel="noopener noreferrer"
                       on:click|stopPropagation>{providerLabel($currentTrack.id)}</a>
                </div>
            {/if}
            {#if $currentTrack.artworkUrl}
                <div class="info-row">
                    <span class="info-label">Artwork</span>
                    <a class="info-value info-link mono" href={$currentTrack.artworkUrl} target="_blank" rel="noopener noreferrer"
                       on:click|stopPropagation>{$currentTrack.artworkUrl}</a>
                </div>
            {/if}
        </div>
    </div>
{/if}

<style>
    .player-wrapper {
        position: fixed;
        bottom: 0;
        left: 260px;
        right: 0;
        z-index: 100;
        background: var(--bg-sidebar);
        border-top: 1px solid var(--border-subtle);
        transition: 0.3s;
    }

    .player-wrapper.expanded {
        top: 0;
        bottom: 0;
        z-index: 500;
        border-top: none;
    }

    .player-layout {
        display: flex;
        width: 100%;
        height: 100%;
    }

    .player-bar {
        padding: 0 24px;
        position: relative;
        height: 90px;
        flex: 1;
        cursor: pointer;
    }

    .expanded-queue-pane {
        width: 400px;
        border-left: 1px solid var(--border-subtle);
        background: var(--bg-surface);
        display: flex;
        flex-direction: column;
    }

    .controls-area {
        display: flex;
        align-items: center;
        justify-content: space-between;
        height: 100%;
    }

    .data-group {
        display: flex;
        align-items: center;
        gap: 16px;
        width: 30%;
    }

    .data-group .artwork {
        width: 56px;
        height: 56px;
        border-radius: 4px;
        flex-shrink: 0;
        font-size: 20px;
        font-weight: 800;
        color: var(--text-primary);
    }

    .data-row {
        display: flex;
        align-items: center;
        flex: 1;
        min-width: 0;
        gap: 8px;
    }

    .metadata {
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
        text-overflow: ellipsis;
    }

    .artist {
        font-size: 12px;
        color: var(--text-secondary);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
    }

    .artist .artist-link {
        cursor: pointer;
    }

    .artist .artist-link:hover {
        color: var(--text-primary);
        text-decoration: underline;
    }

    .album {
        font-size: 11px;
        color: var(--text-secondary);
        opacity: 0.7;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        margin-top: 1px;
    }

    .album-link {
        background: none;
        border: none;
        padding: 0;
        font: inherit;
        color: inherit;
        cursor: pointer;
    }

    .album-link:hover {
        color: var(--text-primary);
        opacity: 1;
        text-decoration: underline;
    }

    .mobile-marquee-viewport {
        min-width: 0;
        width: 100%;
        overflow: hidden;
    }

    .mobile-marquee-track {
        min-width: 0;
        width: 100%;
        white-space: nowrap;
    }

    .mobile-marquee-track.overflowing {
        display: inline-flex;
        align-items: center;
        width: max-content;
        max-width: none;
        animation: marqueeLoop var(--marquee-duration) linear infinite;
        will-change: transform;
    }

    .mobile-marquee-track:not(.overflowing) .mobile-marquee-sep,
    .mobile-marquee-track:not(.overflowing) .mobile-marquee-copy-clone {
        display: none;
    }

    .mobile-marquee-sep {
        display: none;
        width: 26px;
        text-align: center;
        opacity: 0.6;
    }

    .mobile-marquee-track.overflowing .mobile-marquee-sep {
        display: inline-flex;
        align-items: center;
        justify-content: center;
    }

    .actions {
        display: flex;
        align-items: center;
        gap: 4px;
        flex-shrink: 0;
    }

    .action-btn {
        background: transparent;
        padding: 8px;
        color: var(--text-secondary);
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
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

    .progress-hitbox {
        width: 100%;
        height: 16px;
        position: absolute;
        top: -8px;
        left: 0;
        cursor: pointer;
        display: flex;
        align-items: center;
        z-index: 10;
    }

    .progress-bg {
        width: 100%;
        height: 3px;
        background: var(--bg-hover);
        position: relative;
        border-radius: 2px;
    }

    .progress-bar {
        height: 100%;
        background: var(--text-primary);
        position: relative;
        border-radius: 2px;
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
        box-shadow: 0 2px 4px rgba(0, 0, 0, 0.5);
    }

    .progress-hitbox:hover .progress-bar,
    .progress-hitbox.dragging .progress-bar {
        background: var(--accent-color);
    }

    .progress-hitbox:hover .progress-thumb,
    .progress-hitbox.dragging .progress-thumb {
        opacity: 1;
    }

    .progress-hitbox.dragging {
        cursor: grabbing;
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
        padding: 8px;
        color: var(--text-secondary);
    }

    .transport-btn:hover {
        color: var(--text-primary);
    }

    .play-btn {
        width: 32px;
        height: 32px;
        padding: 0;
        border-radius: 50%;
        background: var(--text-primary);
        color: var(--bg-base);
        display: flex;
        align-items: center;
        justify-content: center;
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

    .track-info-panel {
        position: fixed;
        bottom: 90px;
        right: 0;
        width: 380px;
        background: var(--bg-elevated);
        border: 1px solid var(--border-subtle);
        border-radius: 8px 8px 0 0;
        padding: 16px;
        box-shadow: 0 -8px 24px rgba(0, 0, 0, 0.4);
        z-index: 600;
    }

    .info-header {
        display: flex;
        align-items: center;
        gap: 10px;
        margin-bottom: 12px;
    }

    .info-title {
        font-size: 13px;
        font-weight: 700;
        color: var(--text-primary);
        flex: 1;
    }

    .provider-badge {
        font-size: 11px;
        font-weight: 600;
        background: var(--accent-color);
        color: var(--bg-base);
        padding: 2px 8px;
        border-radius: 10px;
    }

    .info-close {
        background: transparent;
        padding: 4px;
        color: var(--text-secondary);
        display: flex;
        align-items: center;
        justify-content: center;
    }

    .info-close:hover {
        color: var(--text-primary);
    }

    .info-rows {
        display: flex;
        flex-direction: column;
        gap: 8px;
    }

    .info-row {
        display: flex;
        gap: 12px;
        align-items: baseline;
    }

    .info-label {
        font-size: 11px;
        font-weight: 600;
        text-transform: uppercase;
        color: var(--text-secondary);
        min-width: 80px;
        flex-shrink: 0;
    }

    .info-value {
        font-size: 12px;
        color: var(--text-primary);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        min-width: 0;
    }

    .info-value.mono {
        font-family: monospace;
        font-size: 11px;
    }

    .info-link {
        color: var(--accent-color);
        text-decoration: none;
    }

    .info-link:hover {
        text-decoration: underline;
    }

    .expanded-header, .expanded-time {
        display: none;
    }

    @keyframes marqueeLoop {
        0% {
            transform: translateX(0);
        }
        100% {
            transform: translateX(calc(-1 * var(--marquee-shift)));
        }
    }

    .player-wrapper.expanded {
        background: var(--bg-surface);
        height: 100dvh;
        border-radius: 0;
        margin: 0;
    }

    .player-wrapper.expanded .player-bar {
        display: flex;
        flex-direction: row;
        align-items: center;
        height: 100%;
        padding: 40px;
        gap: 60px;
        cursor: default;
    }

    .player-wrapper.expanded .expanded-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: 32px;
        color: var(--text-secondary);
        font-size: 12px;
        font-weight: 700;
        letter-spacing: 0.1em;
        text-transform: uppercase;
        flex-shrink: 0;
        position: absolute;
        top: 40px;
        left: 40px;
        right: 40px;
    }

    .player-wrapper.expanded .controls-area {
        display: flex;
        flex-direction: column;
        flex: 1;
        justify-content: center;
        padding-bottom: 0;
        padding-top: 60px;
    }

    .player-wrapper.expanded .data-group {
        display: flex;
        flex-direction: column;
        align-items: center;
        width: 100%;
        margin-top: 0;
        margin-bottom: 0;
        flex: 1;
        justify-content: center;
    }

    .player-wrapper.expanded .data-group .artwork {
        width: 100%;
        max-width: 480px;
        height: auto;
        aspect-ratio: 1;
        border-radius: 12px;
        box-shadow: 0 16px 32px rgba(0, 0, 0, 0.5);
        font-size: 72px;
    }

    .player-wrapper.expanded .data-row {
        display: flex;
        flex-direction: column;
        align-items: flex-start;
        justify-content: center;
        width: 100%;
        margin-top: 16px;
    }

    .player-wrapper.expanded .metadata {
        gap: 6px;
        margin-bottom: 24px;
        width: 100%;
    }

    .player-wrapper.expanded .metadata .title {
        font-size: 36px;
        font-weight: 800;
        line-height: 1.1;
        margin-bottom: 0;
        white-space: normal;
    }

    .player-wrapper.expanded .metadata .artist {
        font-size: 20px;
        line-height: 1.3;
    }

    .player-wrapper.expanded .metadata .album {
        font-size: 16px;
        line-height: 1.35;
        color: var(--text-primary);
        opacity: 0.82;
        margin-top: 0;
        white-space: normal;
        overflow: visible;
        text-overflow: clip;
    }

    .player-wrapper.expanded .metadata .album-link {
        color: inherit;
    }

    .player-wrapper.expanded .actions {
        display: flex;
        gap: 12px;
        margin-bottom: 24px;
        width: 100%;
    }

    .player-wrapper.expanded .actions .action-btn {
        padding: 8px;
    }

    .player-wrapper.expanded .actions .action-btn svg {
        width: 24px;
        height: 24px;
    }

    .player-wrapper.expanded .progress-hitbox {
        position: relative;
        bottom: auto;
        left: auto;
        width: 100%;
        height: 24px;
        margin-top: 16px;
        display: flex;
        align-items: center;
    }

    .player-wrapper.expanded .progress-bg {
        height: 4px;
        border-radius: 2px;
        width: 100%;
    }

    .player-wrapper.expanded .progress-thumb {
        display: block;
        width: 12px;
        height: 12px;
        opacity: 1;
    }

    .player-wrapper.expanded .expanded-time {
        width: 100%;
        display: flex;
        justify-content: space-between;
        font-size: 12px;
        color: var(--text-secondary);
        font-variant-numeric: tabular-nums;
        margin-top: -4px;
        margin-bottom: 24px;
    }

    .player-wrapper.expanded .transport-group {
        width: 100%;
        display: flex;
        justify-content: center;
        align-items: center;
        gap: 32px;
        margin-bottom: 32px;
    }

    .player-wrapper.expanded .transport-btn {
        display: flex;
        padding: 12px;
    }

    .player-wrapper.expanded .transport-btn svg {
        width: 32px;
        height: 32px;
    }

    .player-wrapper.expanded .play-btn {
        width: 72px;
        height: 72px;
        background: var(--text-primary);
        color: var(--bg-base);
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        padding: 0;
    }

    .player-wrapper.expanded .play-btn svg {
        width: 36px;
        height: 36px;
    }

    .player-wrapper.expanded .right-group {
        display: flex;
        width: 100%;
        justify-content: space-between;
    }

    .player-wrapper.expanded .desktop-time,
    .player-wrapper.expanded .action-vol {
        display: none;
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

    @media (max-width: 900px) {
        .player-wrapper.expanded .player-bar {
            flex-direction: column;
            gap: 20px;
            padding: 24px;
        }

        .player-wrapper.expanded .expanded-header {
            position: relative;
            top: auto;
            left: auto;
            right: auto;
        }

        .player-wrapper.expanded .controls-area {
            padding-top: 0;
        }

        .player-wrapper.expanded .metadata .title {
            font-size: 24px;
        }

        .player-wrapper.expanded .metadata .artist {
            font-size: 16px;
        }

        .player-wrapper.expanded .data-group {
            gap: 20px;
            margin-top: auto;
            margin-bottom: auto;
        }

        .player-wrapper.expanded .data-group .artwork {
            max-width: 320px;
        }
    }

    @media (max-width: 600px) {
        .player-wrapper.expanded {
            left: 0;
        }

        .player-layout {
            flex-direction: column;
            overflow-y: auto;
        }

        .expanded-queue-pane {
            width: 100%;
            border-left: none;
            border-top: 1px solid var(--border-subtle);
        }

        .player-wrapper {
            left: 0;
            bottom: 65px;
            background: var(--bg-elevated);
            border-radius: 8px;
            margin: 0 8px 8px 8px;
            border: none;
            box-shadow: 0 8px 24px rgba(0, 0, 0, 0.6);
            transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
        }

        .player-bar {
            height: 60px;
            padding: 0 12px;
        }

        .player-wrapper:not(.expanded) .idle-text {
            line-height: 60px;
        }

        .player-wrapper:not(.expanded) .progress-hitbox {
            top: auto;
            bottom: 0;
            left: 8px;
            width: calc(100% - 16px);
            height: 4px;
        }

        .player-wrapper:not(.expanded) .progress-bg {
            height: 2px;
        }

        .player-wrapper:not(.expanded) .progress-thumb {
            display: none;
        }

        .player-wrapper:not(.expanded) .controls-area {
            flex-direction: row;
            flex-wrap: nowrap;
            gap: 8px;
        }

        .player-wrapper:not(.expanded) .data-group {
            width: auto;
            flex: 1;
            gap: 10px;
            min-width: 0;
        }

        .player-wrapper:not(.expanded) .data-group .artwork {
            width: 44px;
            height: 44px;
        }

        .player-wrapper:not(.expanded) .metadata {
            flex: 1;
            overflow: hidden;
            min-width: 0;
        }

        .player-wrapper:not(.expanded) .metadata .title {
            font-size: 13px;
            line-height: 1.2;
        }

        .player-wrapper:not(.expanded) .metadata .artist {
            font-size: 11px;
        }

        .player-wrapper:not(.expanded) .metadata {
            gap: 2px;
        }

        .player-wrapper:not(.expanded) .metadata .album {
            display: none;
        }

        .player-wrapper:not(.expanded) .actions .action-add {
            display: none !important;
        }

        .player-wrapper:not(.expanded) .actions .action-like,
        .player-wrapper:not(.expanded) .actions .action-dislike {
            padding: 6px;
        }

        .player-wrapper:not(.expanded) .right-group {
            display: none;
        }

        .player-wrapper:not(.expanded) .transport-group {
            width: auto;
            gap: 6px;
            justify-content: flex-end;
            align-items: center;
        }

        .player-wrapper:not(.expanded) .transport-btn.prev-btn {
            display: none;
        }

        .player-wrapper:not(.expanded) .transport-btn.next-btn {
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 6px;
        }

        .player-wrapper:not(.expanded) .transport-btn.next-btn svg {
            width: 20px;
            height: 20px;
        }

        .player-wrapper:not(.expanded) .play-btn {
            width: 36px;
            height: 36px;
            background: transparent;
            color: var(--text-primary);
        }

        .player-wrapper:not(.expanded) .play-btn svg {
            width: 28px;
            height: 28px;
        }

        .track-info-panel {
            position: fixed;
            bottom: 0;
            left: 0;
            right: 0;
            width: 100%;
            border-radius: 16px 16px 0 0;
            z-index: 600;
            max-height: 70dvh;
            overflow-y: auto;
        }
    }
</style>
