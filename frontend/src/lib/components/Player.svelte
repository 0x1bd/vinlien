<script lang="ts">
    import {
        queue,
        currentTrackIndex,
        currentTrack,
        isPlaying,
        userPlaylists,
        repeatMode,
        volume,
        isMuted,
        showVolumeSlider,
        trackToAdd,
        showQueuePanel
    } from '$lib/utils/store';
    import {apiRequest} from '$lib/utils/api';
    import {addToast} from '$lib/utils/toast';
    import {audioManager, audioProgress, currentTimeDisplay, durationDisplay} from '$lib/utils/AudioManager';
    import QueuePanel from './QueuePanel.svelte';
    import {goto} from '$app/navigation';

    $: likedPlaylist = $userPlaylists.find(p => p.name === 'Liked Songs');
    $: dislikedPlaylist = $userPlaylists.find(p => p.name === 'Disliked Songs');
    $: isLiked = $currentTrack && likedPlaylist?.tracks.some(t => t.id === $currentTrack.id);
    $: isDisliked = $currentTrack && dislikedPlaylist?.tracks.some(t => t.id === $currentTrack.id);

    let isMobileExpanded = false;
    let touchStartY = 0;

    function togglePlay() {
        $isPlaying = !$isPlaying;
    }

    function handleSeek(e: MouseEvent) {
        const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
        audioManager.seek(((e.clientX - rect.left) / rect.width) * 100);
    }

    function handleTouchStart(e: TouchEvent) {
        if (window.innerWidth <= 600 && isMobileExpanded) {
            touchStartY = e.touches[0].clientY;
        }
    }

    function handleTouchEnd(e: TouchEvent) {
        if (window.innerWidth <= 600 && isMobileExpanded) {
            const touchEndY = e.changedTouches[0].clientY;
            if (touchEndY - touchStartY > 80) {
                isMobileExpanded = false;
            }
        }
    }

    function handlePlayerBarClick(e: MouseEvent) {
        if (!$currentTrack) return;
        if (window.innerWidth <= 600 && !isMobileExpanded && !(e.target as Element).closest('button') && !(e.target as Element).closest('.progress-hitbox') && !(e.target as Element).closest('.expanded-header')) {
            isMobileExpanded = true;
        }
    }

    async function toggleLike() {
        if (!$currentTrack) return;
        const prevLiked = isLiked;
        isLiked = !isLiked;
        if (isLiked) isDisliked = false;
        addToast(isLiked ? 'Added to Liked Songs' : 'Removed from Liked Songs', 'success');
        try {
            await apiRequest('/api/playlists/liked/toggle', {method: 'POST', body: $currentTrack});
            const res = await apiRequest('/api/playlists');
            if (res) $userPlaylists = res;
        } catch (e) {
            isLiked = prevLiked;
        }
    }

    async function toggleDislike() {
        if (!$currentTrack) return;
        const prevDisliked = isDisliked;
        isDisliked = !isDisliked;
        if (isDisliked) isLiked = false;
        addToast(isDisliked ? 'Will not recommend this song' : 'Removed from Dislikes', 'info');
        try {
            await apiRequest('/api/playlists/disliked/toggle', {method: 'POST', body: $currentTrack});
            const res = await apiRequest('/api/playlists');
            if (res) $userPlaylists = res;
            if (isDisliked && $isPlaying) {
                audioManager.playNext(true);
            }
        } catch (e) {
            isDisliked = prevDisliked;
        }
    }
</script>

<QueuePanel/>
<div class="player-wrapper" class:expanded={isMobileExpanded}>
    <!-- svelte-ignore a11y-click-events-have-key-events -->
    <!-- svelte-ignore a11y-no-noninteractive-element-interactions -->
    <div class="player-bar"
         on:click={handlePlayerBarClick}
         on:touchstart={handleTouchStart}
         on:touchend={handleTouchEnd}
         role="presentation"
    >
        {#if $currentTrack}
            {#if isMobileExpanded}
                <div class="expanded-header">
                    <button class="icon-btn" on:click|stopPropagation={() => isMobileExpanded = false}>
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
                    <img src={$currentTrack.artworkUrl} alt="Art">
                    <div class="data-row">
                        <div class="metadata">
                            <div class="title">{$currentTrack.title}</div>
                            <!-- svelte-ignore a11y-click-events-have-key-events -->
                            <!-- svelte-ignore a11y-no-static-element-interactions -->
                            <div class="artist artist-link"
                                 on:click|stopPropagation={() => { isMobileExpanded = false; if ($currentTrack) goto(`/artist/${encodeURIComponent($currentTrack.artist)}`); }}>
                                {$currentTrack.artist}
                            </div>
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
                                    on:click|stopPropagation={() => { isMobileExpanded = false; $trackToAdd = $currentTrack; }}
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
                <div class="progress-hitbox" on:click|stopPropagation={handleSeek} role="slider">
                    <div class="progress-bg">
                        <div class="progress-bar" style="width: {$audioProgress}%">
                            <div class="progress-thumb"></div>
                        </div>
                    </div>
                </div>

                {#if isMobileExpanded}
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
                            <!-- Repeat One Icon -->
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
                            <!-- Repeat All / Off Icon -->
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
                    <!-- Removed isMobileExpanded = false to keep player open when queue shown over it -->
                    <button class="action-btn action-queue" class:active={$showQueuePanel}
                            on:click|stopPropagation={() => { $showQueuePanel = !$showQueuePanel; }}>
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                             stroke-width="2">
                            <line x1="8" y1="6" x2="21" y2="6"></line>
                            <line x1="8" y1="12" x2="21" y2="12"></line>
                            <line x1="8" y1="18" x2="21" y2="18"></line>
                        </svg>
                    </button>
                </div>
            </div>
        {:else}
            <div class="idle-text">Pick a track to start listening</div>
        {/if}
    </div>
</div>

<style>
    .player-wrapper {
        position: fixed;
        bottom: 0;
        left: 260px;
        right: 0;
        z-index: 100;
        background: #000;
        border-top: 1px solid var(--border-subtle);
        transition: 0.3s;
    }

    .player-bar {
        padding: 0 24px;
        position: relative;
        height: 90px;
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

    .data-group img {
        width: 56px;
        height: 56px;
        border-radius: 4px;
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
        background: #fff;
        border-radius: 50%;
        opacity: 0;
        box-shadow: 0 2px 4px rgba(0, 0, 0, 0.5);
    }

    .progress-hitbox:hover .progress-bar {
        background: var(--accent-color);
    }

    .progress-hitbox:hover .progress-thumb {
        opacity: 1;
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
        color: #000;
        display: flex;
        align-items: center;
        justify-content: center;
    }

    .play-btn:hover {
        transform: scale(1.05);
        background: #fff;
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

    .expanded-header, .expanded-time {
        display: none;
    }

    @media (max-width: 600px) {
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
            padding: 0 8px;
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
            gap: 4px;
        }

        .player-wrapper:not(.expanded) .data-group {
            width: auto;
            flex: 1;
            gap: 8px;
        }

        .player-wrapper:not(.expanded) .data-group img {
            width: 44px;
            height: 44px;
        }

        .player-wrapper:not(.expanded) .metadata {
            flex: 1;
            overflow: hidden;
        }

        .player-wrapper:not(.expanded) .metadata .title {
            font-size: 13px;
        }

        .player-wrapper:not(.expanded) .metadata .artist {
            font-size: 11px;
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
            gap: 4px;
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

        .player-wrapper.expanded {
            position: fixed;
            bottom: 0;
            left: 0;
            right: 0;
            top: 0;
            margin: 0;
            border-radius: 0;
            background: var(--bg-surface);
            z-index: 300;
            height: 100dvh;
        }

        .player-wrapper.expanded .player-bar {
            display: flex;
            flex-direction: column;
            height: 100%;
            padding: 24px;
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
        }

        .player-wrapper.expanded .controls-area {
            display: flex;
            flex-direction: column;
            flex: 1;
            justify-content: flex-end;
            padding-bottom: env(safe-area-inset-bottom, 24px);
        }

        .player-wrapper.expanded .data-group {
            display: flex;
            flex-direction: column;
            align-items: center;
            width: 100%;
            gap: 32px;
            margin-top: auto;
            margin-bottom: auto;
        }

        .player-wrapper.expanded .data-group img {
            width: 100%;
            max-width: 360px;
            height: auto;
            aspect-ratio: 1;
            border-radius: 12px;
            box-shadow: 0 16px 32px rgba(0, 0, 0, 0.5);
        }

        .player-wrapper.expanded .data-row {
            align-items: center;
            justify-content: space-between;
            width: 100%;
        }

        .player-wrapper.expanded .metadata .title {
            font-size: 24px;
            font-weight: 800;
            margin-bottom: 4px;
            white-space: normal;
        }

        .player-wrapper.expanded .metadata .artist {
            font-size: 16px;
        }

        .player-wrapper.expanded .actions {
            gap: 12px;
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
            color: #000;
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
    }
</style>