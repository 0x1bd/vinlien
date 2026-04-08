<script lang="ts">
    import {page} from '$app/stores';
    import {queue, currentTrackIndex, isPlaying, userPlaylists} from '$lib/utils/store';
    import {apiRequest} from '$lib/utils/api';
    import TrackRow from '$lib/components/TrackRow.svelte';
    import type {Playlist} from '$lib/utils/types';

    $: selectedPlaylistId = $page.params.id;
    let playlist: Playlist | null = null;
    let draggedIdx: number | null = null;

    let showEditModal = false;
    let editName = "";
    let editDescription = "";
    let editImageUrl = "";
    let isSaving = false;

    $: if (selectedPlaylistId) {
        apiRequest('/api/playlists').then((all: Playlist[]) => {
            playlist = all.find(p => p.id === selectedPlaylistId) || null;
            if (playlist) {
                editName = playlist.name;
                editDescription = playlist.description || "";
                editImageUrl = playlist.imageUrl || "";
            }
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
            showEditModal = false;
        } catch (e) {
            console.error("Failed to update playlist info", e);
        } finally {
            isSaving = false;
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
        if (e.dataTransfer) {
            e.dataTransfer.dropEffect = 'move';
        }
    }

    async function handleDrop(e: DragEvent, idx: number) {
        e.preventDefault();
        if (draggedIdx === null || draggedIdx === idx || !playlist) return;

        const newTracks = [...playlist.tracks];
        const [movedTrack] = newTracks.splice(draggedIdx, 1);
        newTracks.splice(idx, 0, movedTrack);

        playlist.tracks = newTracks;
        draggedIdx = null;

        try {
            await apiRequest(`/api/playlists/${playlist.id}/tracks`, {
                method: 'PUT',
                body: playlist.tracks
            });
            const all: Playlist[] = await apiRequest('/api/playlists');
            userPlaylists.set(all);
        } catch (err) {
            console.error("Failed to reorder tracks", err);
        }
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
                    <button class="icon-btn" on:click={() => showEditModal = true} title="Edit Details">
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
                    on:drop={(e) => handleDrop(e, i)}
                    class="draggable-row"
                    class:dragging={draggedIdx === i}
            >
                <TrackRow {track} onPlay={() => playTrackAtIndex(i)}/>
            </div>
        {/each}
        {#if playlist.tracks.length === 0}
            <div class="empty-state">
                <p>It's quiet in here...</p>
                <p style="font-size: 13px; color: var(--text-secondary);">Search for tracks and add them to this
                    playlist.</p>
            </div>
        {/if}
    </div>
{:else}
    <div class="empty-state">Loading playlist...</div>
{/if}

<!-- Edit Playlist Modal -->
{#if showEditModal}
    <!-- svelte-ignore a11y-click-events-have-key-events -->
    <!-- svelte-ignore a11y-no-static-element-interactions -->
    <div class="modal-backdrop" on:click={() => showEditModal = false}>
        <div class="modal-content" on:click|stopPropagation>
            <div class="modal-header">
                <h3>Edit Details</h3>
                <button class="icon-btn" on:click={() => showEditModal = false}>
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
                <input id="pl-img" type="text" bind:value={editImageUrl} placeholder="https://..."/>
            </div>

            <div class="modal-actions">
                <button on:click={savePlaylistInfo} disabled={isSaving || !editName.trim()}>
                    {isSaving ? 'Saving...' : 'Save'}
                </button>
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

    .track-list {
        display: flex;
        flex-direction: column;
        gap: 2px;
    }

    .draggable-row {
        transition: opacity 0.2s;
    }

    .draggable-row.dragging {
        opacity: 0.4;
        background: var(--bg-hover);
        border-radius: 6px;
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
        justify-content: flex-end;
        margin-top: 8px;
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
    }
</style>