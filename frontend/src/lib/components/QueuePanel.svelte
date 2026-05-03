<script lang="ts">
    import {
        queue,
        currentTrackIndex,
        showQueuePanel,
        userPlaylists,
        similarTracks,
        isFetchingSimilar,
        currentTrack,
        fetchSimilarTracksIfNeeded
    } from '$lib/utils/store';
    import {apiRequest} from '$lib/utils/api';
    import {addToast} from '$lib/utils/toast';
    import TrackRow from './TrackRow.svelte';
    import { fade } from 'svelte/transition';

    export let inline = false;

    let innerWidth = 0;
    let touchStartX = 0;
    let showNameInput = false;
    let newPlaylistName = '';
    let isSavingQueue = false;

    let activeTab: 'queue' | 'similar' = 'queue';

    $: showSimilarTab = !inline || innerWidth <= 1550;

    $: if (!showSimilarTab && activeTab === 'similar') {
        activeTab = 'queue';
    }

    $: if (activeTab === 'similar' && $currentTrack && (inline || $showQueuePanel)) {
        fetchSimilarTracksIfNeeded($currentTrack);
    }

    function handleTouchStart(e: TouchEvent) {
        if (window.innerWidth <= 600) {
            touchStartX = e.touches[0].clientX;
        }
    }

    function handleTouchEnd(e: TouchEvent) {
        if (window.innerWidth <= 600) {
            const touchEndX = e.changedTouches[0].clientX;
            const diffX = touchEndX - touchStartX;
            if (diffX > 80) {
                $showQueuePanel = false;
            }
        }
    }

    async function saveQueueAsPlaylist() {
        const name = newPlaylistName.trim();
        if (!name || $queue.length === 0 || isSavingQueue) return;
        isSavingQueue = true;
        try {
            const playlist = await apiRequest('/api/playlists', {method: 'POST', body: {name}});
            await apiRequest(`/api/playlists/${playlist.id}/tracks`, {method: 'PUT', body: $queue});
            const all = await apiRequest('/api/playlists');
            userPlaylists.set(all);
            addToast(`Saved ${$queue.length} tracks to "${name}"`, 'success');
            newPlaylistName = '';
            showNameInput = false;
        } catch {
            addToast('Failed to create playlist', 'error');
        } finally {
            isSavingQueue = false;
        }
    }

    function handleNameKeydown(e: KeyboardEvent) {
        if (e.key === 'Enter') saveQueueAsPlaylist();
        if (e.key === 'Escape') { showNameInput = false; newPlaylistName = ''; }
    }

    function playSimilar(track: any) {
        $queue = [...$queue, track];
        $currentTrackIndex = $queue.length - 1;
    }
</script>

<svelte:window bind:innerWidth />

<div class="queue-panel" class:open={$showQueuePanel} class:inline={inline}
     on:touchstart={handleTouchStart}
     on:touchend={handleTouchEnd}>
    <div class="header">
        {#if showSimilarTab}
            <div class="tabs">
                <button class:active={activeTab === 'queue'} on:click={() => activeTab = 'queue'}>Queue</button>
                <button class:active={activeTab === 'similar'} on:click={() => activeTab = 'similar'}>Similar</button>
            </div>
        {:else}
            <h3>Queue</h3>
        {/if}

        <div class="header-actions">
            {#if activeTab === 'queue' && $queue.length > 0}
                <button class="icon-btn save-btn" class:active={showNameInput}
                        on:click={() => { showNameInput = !showNameInput; if (!showNameInput) newPlaylistName = ''; }}
                        title="Save queue as playlist">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"></path>
                        <polyline points="17 21 17 13 7 13 7 21"></polyline>
                        <polyline points="7 3 7 8 15 8"></polyline>
                    </svg>
                </button>
            {/if}
            {#if !inline}
                <button class="icon-btn" on:click={() => $showQueuePanel = false}>
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <line x1="18" y1="6" x2="6" y2="18"></line>
                        <line x1="6" y1="6" x2="18" y2="18"></line>
                    </svg>
                </button>
            {/if}
        </div>
    </div>

    {#if activeTab === 'queue'}
        {#if showNameInput}
            <div class="save-row" in:fade={{ duration: 150 }}>
                <!-- svelte-ignore a11y-autofocus -->
                <input
                        autofocus
                        class="save-input"
                        bind:value={newPlaylistName}
                        placeholder="Playlist name…"
                        on:keydown={handleNameKeydown}
                />
                <button class="save-confirm-btn" on:click={saveQueueAsPlaylist}
                        disabled={isSavingQueue || !newPlaylistName.trim()}>
                    {isSavingQueue ? '…' : 'Save'}
                </button>
            </div>
        {/if}

        <div class="queue-list" in:fade={{ duration: 150 }}>
            {#each $queue as track, i}
                <div class="queue-item" class:playing={i === $currentTrackIndex}>
                    <TrackRow {track} onPlay={() => $currentTrackIndex = i}/>
                </div>
            {/each}
        </div>
    {:else}
        <div class="queue-list similar-list" in:fade={{ duration: 150 }}>
            {#if $isFetchingSimilar}
                <div class="msg">Loading similar tracks...</div>
            {:else if $similarTracks.length === 0}
                <div class="msg">No similar tracks found.</div>
            {:else}
                {#each $similarTracks as track}
                    <div class="queue-item">
                        <TrackRow {track} onPlay={() => playSimilar(track)}/>
                    </div>
                {/each}
            {/if}
        </div>
    {/if}
</div>

<style>
    .queue-panel {
        position: fixed;
        right: 0;
        top: 0;
        bottom: 90px;
        width: 320px;
        background: var(--bg-surface);
        border-left: 1px solid var(--border-subtle);
        transform: translateX(100%);
        transition: 0.3s;
        z-index: 400;
        display: flex;
        flex-direction: column;
    }

    .queue-panel.open {
        transform: translateX(0);
    }

    .queue-panel.inline {
        position: relative;
        width: 100%;
        height: 100%;
        transform: none;
        border: none;
        z-index: 1;
        bottom: auto;
        right: auto;
        top: auto;
    }

    .header {
        padding: 0 16px;
        display: flex;
        justify-content: space-between;
        align-items: center;
        border-bottom: 1px solid var(--border-subtle);
        flex-shrink: 0;
        height: 60px;
    }

    .header h3 {
        font-size: 16px;
        font-weight: 700;
        margin: 0;
    }

    .tabs {
        display: flex;
        gap: 16px;
    }

    .tabs button {
        background: none;
        border: none;
        padding: 0;
        font-size: 16px;
        font-weight: 700;
        color: var(--text-secondary);
        cursor: pointer;
        transition: color 0.2s ease;
    }

    .tabs button.active {
        color: var(--text-primary);
    }

    .tabs button:hover:not(.active) {
        color: var(--text-primary);
        opacity: 0.8;
    }

    .header-actions {
        display: flex;
        align-items: center;
        gap: 4px;
    }

    .icon-btn {
        background: transparent;
        color: var(--text-secondary);
        padding: 6px;
        border-radius: 4px;
        display: flex;
        align-items: center;
        justify-content: center;
        border: none;
        cursor: pointer;
    }

    .icon-btn:hover {
        color: var(--text-primary);
        background: var(--bg-hover);
    }

    .save-btn.active {
        color: var(--accent-color);
        background: color-mix(in srgb, var(--accent-color) 15%, transparent);
    }

    .save-row {
        display: flex;
        gap: 8px;
        padding: 10px 12px;
        border-bottom: 1px solid var(--border-subtle);
        flex-shrink: 0;
    }

    .save-input {
        flex: 1;
        background: var(--bg-elevated);
        border: 1px solid var(--border-subtle);
        color: var(--text-primary);
        padding: 7px 10px;
        border-radius: 6px;
        font-size: 13px;
        outline: none;
        font-family: inherit;
    }

    .save-input:focus {
        border-color: var(--accent-color);
    }

    .save-confirm-btn {
        background: var(--accent-color);
        color: #fff;
        padding: 7px 14px;
        border-radius: 6px;
        font-size: 13px;
        font-weight: 600;
        white-space: nowrap;
        border: none;
        cursor: pointer;
    }

    .save-confirm-btn:disabled {
        opacity: 0.4;
        cursor: not-allowed;
    }

    .save-confirm-btn:not(:disabled):hover {
        filter: brightness(0.88);
    }

    .queue-list {
        flex: 1;
        overflow-y: auto;
        padding: 8px;
    }

    .queue-item.playing {
        background: color-mix(in srgb, var(--accent-color) 12%, transparent);
        border-radius: 6px;
    }

    .msg {
        padding: 24px;
        text-align: center;
        color: var(--text-secondary);
        font-size: 14px;
    }

    @media (max-width: 600px) {
        .queue-panel:not(.inline) {
            width: 100%;
            bottom: 0;
            top: 0;
            height: 100dvh;
        }

        .header h3, .tabs button {
            font-size: 18px;
        }
    }
</style>
