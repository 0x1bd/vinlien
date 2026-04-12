<script lang="ts">
    import {queue, currentTrackIndex, showQueuePanel, userPlaylists} from '$lib/utils/store';
    import {apiRequest} from '$lib/utils/api';
    import {addToast} from '$lib/utils/toast';
    import TrackRow from './TrackRow.svelte';

    let touchStartX = 0;
    let showNameInput = false;
    let newPlaylistName = '';
    let isSavingQueue = false;

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
</script>

<div class="queue-panel" class:open={$showQueuePanel}
     on:touchstart={handleTouchStart}
     on:touchend={handleTouchEnd}>
    <div class="header">
        <h3>Queue</h3>
        <div class="header-actions">
            {#if $queue.length > 0}
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
            <button class="icon-btn" on:click={() => $showQueuePanel = false}>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <line x1="18" y1="6" x2="6" y2="18"></line>
                    <line x1="6" y1="6" x2="18" y2="18"></line>
                </svg>
            </button>
        </div>
    </div>

    {#if showNameInput}
        <div class="save-row">
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

    <div class="queue-list">
        {#each $queue as track, i}
            <div class="queue-item" class:playing={i === $currentTrackIndex}>
                <TrackRow {track} onPlay={() => $currentTrackIndex = i}/>
            </div>
        {/each}
    </div>
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

    .header {
        padding: 16px;
        display: flex;
        justify-content: space-between;
        align-items: center;
        border-bottom: 1px solid var(--border-subtle);
        flex-shrink: 0;
    }

    .header h3 {
        font-size: 16px;
        font-weight: 700;
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

    @media (max-width: 600px) {
        .queue-panel {
            width: 100%;
            bottom: 0;
            top: 0;
            height: 100dvh;
        }

        .header h3 {
            font-size: 18px;
        }
    }
</style>
