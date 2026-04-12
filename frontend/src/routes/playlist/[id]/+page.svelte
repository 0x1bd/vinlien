<script lang="ts">
    import {page} from '$app/stores';
    import {goto} from '$app/navigation';
    import {queue, currentTrackIndex, isPlaying, userPlaylists} from '$lib/utils/store';
    import {apiRequest} from '$lib/utils/api';
    import {addToast} from '$lib/utils/toast';
    import TrackRow from '$lib/components/TrackRow.svelte';
    import type {Playlist} from '$lib/utils/types';

    $: selectedPlaylistId = $page.params.id;
    let playlist: Playlist | null = null;
    let draggedIdx: number | null = null;
    let dragoverIdx: number | null = null;

    let showEditModal = false;
    let editName = "";
    let editDescription = "";
    let editImageUrl = "";
    let isSaving = false;
    let isDeleting = false;
    let showDeleteConfirm = false;

    $: if (selectedPlaylistId) {
        apiRequest('/api/playlists').then((all: Playlist[]) => {
            playlist = all.find(p => p.id === selectedPlaylistId) || null;
        });
    }

    $: isSystemPlaylist = playlist?.name === 'Liked Songs' || playlist?.name === 'Disliked Songs';
    $: displayImage = playlist?.imageUrl || (playlist?.tracks.length ? playlist.tracks[0].artworkUrl : null);
    $: totalDurationMs = playlist?.tracks.reduce((acc, t) => acc + (t.durationMs || 0), 0) || 0;

    function formatTotalTime(ms: number) {
        if (!ms) return "0 min";
        const totalMinutes = Math.floor(ms / 60000);
        const hours = Math.floor(totalMinutes / 60);
        const mins = totalMinutes % 60;
        if (hours > 0) return `${hours} hr ${mins} min`;
        return `${mins} min`;
    }

    function playPlaylist() {
        if (!playlist?.tracks.length) return;
        $queue = [...playlist.tracks];
        $currentTrackIndex = 0;
        $isPlaying = true;
    }

    function playTrackAtIndex(index: number) {
        if (!playlist) return;
        $queue = [...playlist.tracks];
        $currentTrackIndex = index;
        $isPlaying = true;
    }

    function openEditModal() {
        if (!playlist) return;
        editName = playlist.name;
        editDescription = playlist.description || "";
        editImageUrl = playlist.imageUrl || "";
        showDeleteConfirm = false;
        showEditModal = true;
    }

    async function savePlaylistInfo() {
        if (!playlist || !editName.trim()) return;
        isSaving = true;
        try {
            await apiRequest(`/api/playlists/${playlist.id}/info`, {
                method: 'PUT',
                body: {
                    name: editName.trim(),
                    description: editDescription.trim() || null,
                    imageUrl: editImageUrl.trim() || null
                }
            });
            const all: Playlist[] = await apiRequest('/api/playlists');
            userPlaylists.set(all);
            playlist = all.find(p => p.id === playlist!.id) || null;
            addToast('Playlist updated', 'success');
            showEditModal = false;
        } catch (e) {
            addToast('Failed to update playlist', 'error');
        } finally {
            isSaving = false;
        }
    }

    async function deletePlaylist() {
        if (!playlist) return;
        isDeleting = true;
        try {
            await apiRequest(`/api/playlists/${playlist.id}`, {method: 'DELETE'});
            const all: Playlist[] = await apiRequest('/api/playlists');
            userPlaylists.set(all);
            addToast('Playlist deleted', 'info');
            goto('/library');
        } catch (e) {
            addToast('Failed to delete playlist', 'error');
            isDeleting = false;
        }
    }

    function handleDragStart(e: DragEvent, idx: number) {
        draggedIdx = idx;
        if (e.dataTransfer) {
            e.dataTransfer.effectAllowed = 'move';
        }
    }

    function handleDragOver(e: DragEvent, idx: number) {
        e.preventDefault();
        if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';
        if (draggedIdx !== null && draggedIdx !== idx) {
            dragoverIdx = idx;
        }
    }

    function handleDragLeave(e: DragEvent, idx: number) {
        if (dragoverIdx === idx) dragoverIdx = null;
    }

    async function handleDrop(e: DragEvent, idx: number) {
        e.preventDefault();
        dragoverIdx = null;
        if (draggedIdx === null || draggedIdx === idx || !playlist) return;

        const newTracks = [...playlist.tracks];
        const [movedTrack] = newTracks.splice(draggedIdx, 1);
        newTracks.splice(idx, 0, movedTrack);

        playlist = {...playlist, tracks: newTracks};
        draggedIdx = null;

        try {
            await apiRequest(`/api/playlists/${playlist.id}/tracks`, {
                method: 'PUT',
                body: newTracks
            });
        } catch (err) {
            addToast('Failed to reorder tracks', 'error');
        }
        apiRequest('/api/playlists').then((all: Playlist[]) => {
            if (all) userPlaylists.set(all);
        }).catch(() => {});
    }

    function handleDragEnd() {
        draggedIdx = null;
        dragoverIdx = null;
    }
</script>

{#if playlist}
    <div class="playlist-header">
        <div class="playlist-art-wrapper">
            {#if displayImage}
                <img src={displayImage} alt="Playlist Art" class="playlist-art"/>
            {:else}
                <div class="playlist-art fallback">
                    <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                         stroke-width="1.5">
                        <path d="M9 18V5l12-2v13"></path>
                        <circle cx="6" cy="18" r="3"></circle>
                        <circle cx="18" cy="16" r="3"></circle>
                    </svg>
                </div>
            {/if}
        </div>

        <div class="playlist-meta">
            <span class="type-badge">Playlist</span>
            <h2 class="page-title">{playlist.name}</h2>
            {#if playlist.description}
                <p class="description">{playlist.description}</p>
            {/if}
            <div class="subtitle">
                {playlist.tracks.length} songs • {formatTotalTime(totalDurationMs)}
            </div>

            <div class="action-row">
                <button class="play-all-btn" on:click={playPlaylist}>
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                        <path d="M8 5v14l11-7z"/>
                    </svg>
                    Play
                </button>

                {#if !isSystemPlaylist}
                    <button class="icon-btn" on:click={openEditModal} title="Edit Details">
                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                             stroke-width="2">
                            <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path>
                            <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path>
                        </svg>
                    </button>
                {/if}
            </div>
        </div>
    </div>

    <div class="track-list">
        {#each playlist.tracks as track, i (track.id + i)}
            <!-- svelte-ignore a11y-no-static-element-interactions -->
            <div
                draggable="true"
                on:dragstart={(e) => handleDragStart(e, i)}
                on:dragover={(e) => handleDragOver(e, i)}
                on:dragleave={(e) => handleDragLeave(e, i)}
                on:drop={(e) => handleDrop(e, i)}
                on:dragend={handleDragEnd}
                class="draggable-row"
                class:dragging={draggedIdx === i}
                class:dragover={dragoverIdx === i && draggedIdx !== i}
            >
                <TrackRow {track} onPlay={() => playTrackAtIndex(i)}/>
            </div>
        {/each}
        {#if playlist.tracks.length === 0}
            <div class="empty-state">
                <p>It's quiet in here…</p>
                <p style="font-size: 13px; color: var(--text-secondary);">Search for tracks and add them to this
                    playlist.</p>
            </div>
        {/if}
    </div>
{:else}
    <div class="empty-state">Loading playlist…</div>
{/if}

<!-- Edit Playlist Modal -->
{#if showEditModal}
    <!-- svelte-ignore a11y-click-events-have-key-events -->
    <!-- svelte-ignore a11y-no-static-element-interactions -->
    <div class="modal-backdrop" on:click={() => { showEditModal = false; showDeleteConfirm = false; }}>
        <div class="modal-content" on:click|stopPropagation>
            <div class="modal-header">
                <h3>Edit Details</h3>
                <button class="icon-btn" on:click={() => { showEditModal = false; showDeleteConfirm = false; }}>
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <line x1="18" y1="6" x2="6" y2="18"></line>
                        <line x1="6" y1="6" x2="18" y2="18"></line>
                    </svg>
                </button>
            </div>

            <div class="form-group">
                <label for="pl-name">Name</label>
                <input id="pl-name" type="text" bind:value={editName} placeholder="Playlist name"/>
            </div>

            <div class="form-group">
                <label for="pl-desc">Description</label>
                <textarea id="pl-desc" bind:value={editDescription} placeholder="Add an optional description"
                          rows="3"></textarea>
            </div>

            <div class="form-group">
                <label for="pl-img">Image URL</label>
                <input id="pl-img" type="text" bind:value={editImageUrl} placeholder="https://…"/>
            </div>

            <div class="modal-actions">
                {#if showDeleteConfirm}
                    <div class="delete-confirm">
                        <span>Delete this playlist?</span>
                        <button class="danger-btn" on:click={deletePlaylist} disabled={isDeleting}>
                            {isDeleting ? 'Deleting…' : 'Yes, delete'}
                        </button>
                        <button class="cancel-btn" on:click={() => showDeleteConfirm = false}>Cancel</button>
                    </div>
                {:else}
                    <button class="delete-icon-btn" on:click={() => showDeleteConfirm = true} title="Delete playlist">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <polyline points="3 6 5 6 21 6"></polyline>
                            <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"></path>
                            <path d="M10 11v6M14 11v6"></path>
                            <path d="M9 6V4h6v2"></path>
                        </svg>
                        Delete playlist
                    </button>
                    <button class="save-btn" on:click={savePlaylistInfo} disabled={isSaving || !editName.trim()}>
                        {isSaving ? 'Saving…' : 'Save'}
                    </button>
                {/if}
            </div>
        </div>
    </div>
{/if}

<style>
    .playlist-header {
        display: flex;
        gap: 32px;
        align-items: flex-end;
        margin-bottom: 48px;
    }

    .playlist-art-wrapper {
        flex-shrink: 0;
    }

    .playlist-art {
        width: 200px;
        height: 200px;
        border-radius: 8px;
        box-shadow: 0 16px 32px rgba(0, 0, 0, 0.5);
        object-fit: cover;
    }

    .playlist-art.fallback {
        background: var(--bg-elevated);
        display: flex;
        align-items: center;
        justify-content: center;
        color: var(--text-secondary);
    }

    .playlist-meta {
        display: flex;
        flex-direction: column;
        gap: 8px;
    }

    .type-badge {
        font-size: 12px;
        font-weight: 700;
        text-transform: uppercase;
        color: var(--text-secondary);
        letter-spacing: 0.1em;
    }

    .page-title {
        font-size: 48px;
        line-height: 1.1;
        margin-bottom: 4px;
        word-break: break-word;
    }

    .description {
        color: var(--text-secondary);
        font-size: 14px;
        margin-bottom: 4px;
        max-width: 600px;
        line-height: 1.4;
    }

    .subtitle {
        color: var(--text-secondary);
        font-size: 14px;
        margin-bottom: 16px;
    }

    .action-row {
        display: flex;
        align-items: center;
        gap: 16px;
    }

    .play-all-btn {
        display: flex;
        align-items: center;
        gap: 8px;
        background: var(--accent-color);
        color: #fff;
        padding: 12px 32px;
        font-size: 16px;
        border-radius: 32px;
    }

    .play-all-btn:hover {
        background: #1d4ed8;
    }

    .icon-btn {
        background: transparent;
        color: var(--text-secondary);
        padding: 8px;
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
    }

    .icon-btn:hover {
        color: var(--text-primary);
        background: var(--bg-hover);
    }

    .track-list {
        display: flex;
        flex-direction: column;
        gap: 2px;
    }

    .draggable-row {
        transition: opacity 0.15s, background 0.1s;
        border-radius: 6px;
    }

    .draggable-row.dragging {
        opacity: 0.35;
        background: var(--bg-hover);
    }

    .draggable-row.dragover {
        background: rgba(37, 99, 235, 0.15);
        outline: 2px solid var(--accent-color);
        outline-offset: -2px;
    }

    .empty-state {
        padding: 48px 0;
        text-align: center;
        color: var(--text-secondary);
    }

    .modal-backdrop {
        position: fixed;
        top: 0;
        left: 0;
        width: 100vw;
        height: 100vh;
        background: rgba(0, 0, 0, 0.7);
        display: flex;
        align-items: center;
        justify-content: center;
        z-index: 1000;
    }

    .modal-content {
        background: var(--bg-surface);
        border: 1px solid var(--border-subtle);
        padding: 24px;
        border-radius: 8px;
        width: 400px;
        display: flex;
        flex-direction: column;
        gap: 16px;
        box-shadow: 0 24px 48px rgba(0, 0, 0, 0.6);
    }

    .modal-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        border-bottom: 1px solid var(--border-subtle);
        padding-bottom: 16px;
    }

    .modal-header h3 {
        font-size: 18px;
        font-weight: 700;
    }

    .form-group {
        display: flex;
        flex-direction: column;
        gap: 6px;
    }

    .form-group label {
        font-size: 12px;
        font-weight: 600;
        color: var(--text-secondary);
    }

    .form-group input, .form-group textarea {
        background: var(--bg-elevated);
        border: 1px solid var(--border-subtle);
        color: var(--text-primary);
        padding: 10px 12px;
        border-radius: 6px;
        font-size: 14px;
        outline: none;
        font-family: inherit;
        resize: none;
    }

    .form-group input:focus, .form-group textarea:focus {
        border-color: var(--accent-color);
    }

    .modal-actions {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-top: 8px;
        gap: 8px;
    }

    .save-btn {
        background: var(--accent-color);
        color: #fff;
        padding: 10px 24px;
        border-radius: 6px;
        font-size: 14px;
        font-weight: 600;
        margin-left: auto;
    }

    .save-btn:hover:not(:disabled) {
        background: #1d4ed8;
    }

    .save-btn:disabled {
        opacity: 0.4;
        cursor: not-allowed;
    }

    .delete-icon-btn {
        display: flex;
        align-items: center;
        gap: 6px;
        background: transparent;
        color: var(--text-secondary);
        font-size: 13px;
        padding: 6px 8px;
        border-radius: 6px;
    }

    .delete-icon-btn:hover {
        color: var(--danger-color, #ef4444);
        background: rgba(239, 68, 68, 0.08);
    }

    .delete-confirm {
        display: flex;
        align-items: center;
        gap: 8px;
        flex: 1;
    }

    .delete-confirm span {
        font-size: 13px;
        color: var(--text-secondary);
        flex: 1;
    }

    .danger-btn {
        background: var(--danger-color, #ef4444);
        color: #fff;
        padding: 8px 16px;
        border-radius: 6px;
        font-size: 13px;
        font-weight: 600;
        white-space: nowrap;
    }

    .danger-btn:disabled {
        opacity: 0.5;
        cursor: not-allowed;
    }

    .cancel-btn {
        background: transparent;
        color: var(--text-secondary);
        padding: 8px 12px;
        border-radius: 6px;
        font-size: 13px;
    }

    .cancel-btn:hover {
        color: var(--text-primary);
        background: var(--bg-hover);
    }

    @media (max-width: 768px) {
        .playlist-header {
            flex-direction: column;
            align-items: center;
            text-align: center;
            gap: 24px;
        }

        .page-title {
            font-size: 32px;
        }

        .action-row {
            justify-content: center;
        }

        .modal-content {
            width: calc(100vw - 32px);
        }
    }
</style>
