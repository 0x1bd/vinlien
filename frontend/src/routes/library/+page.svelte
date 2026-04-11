<script lang="ts">
    import {userPlaylists} from '$lib/utils/store';
    import {apiRequest} from '$lib/utils/api';
    import {addToast} from '$lib/utils/toast';
    import {goto} from '$app/navigation';
    import type {Playlist} from '$lib/utils/types';

    let isCreating = false;
    let newName = '';
    let playlistToDelete: Playlist | null = null;
    let isDeleting = false;

    const SYSTEM = new Set(['Liked Songs', 'Disliked Songs']);

    async function createPlaylist() {
        if (!newName.trim()) return;
        try {
            await apiRequest('/api/playlists', {method: 'POST', body: {name: newName.trim()}});
            const all = await apiRequest('/api/playlists');
            userPlaylists.set(all);
            newName = '';
            isCreating = false;
        } catch (e) {
            console.error(e);
        }
    }

    async function confirmDelete() {
        if (!playlistToDelete || isDeleting) return;
        isDeleting = true;
        try {
            await apiRequest(`/api/playlists/${playlistToDelete.id}`, {method: 'DELETE'});
            const all = await apiRequest('/api/playlists');
            userPlaylists.set(all);
            addToast(`Deleted "${playlistToDelete.name}"`, 'info');
            playlistToDelete = null;
        } catch {
            addToast('Failed to delete playlist', 'error');
        } finally {
            isDeleting = false;
        }
    }
</script>

<div class="header">
    <h2 class="page-title">Your Library</h2>
</div>

<div class="library-actions">
    {#if !isCreating}
        <button on:click={() => isCreating = true}>+ New Playlist</button>
    {:else}
        <div class="create-row">
            <input type="text" bind:value={newName} placeholder="Playlist name..."
                   on:keydown={e => e.key === 'Enter' && createPlaylist()}/>
            <button on:click={createPlaylist}>Save</button>
            <button class="danger" on:click={() => isCreating = false}>Cancel</button>
        </div>
    {/if}
</div>

<div class="playlist-grid">
    {#each $userPlaylists as pl}
        <!-- svelte-ignore a11y-click-events-have-key-events -->
        <!-- svelte-ignore a11y-no-static-element-interactions -->
        <div class="pl-card" on:click={() => goto(`/playlist/${pl.id}`)}>
            <div class="pl-art">
                {#if pl.imageUrl || (pl.tracks.length > 0 && pl.tracks[0].artworkUrl)}
                    <img src={pl.imageUrl || pl.tracks[0].artworkUrl} alt="Cover">
                {:else if pl.name === 'Liked Songs'}
                    <svg width="32" height="32" viewBox="0 0 24 24" fill="currentColor">
                        <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"></path>
                    </svg>
                {:else if pl.name === 'Disliked Songs'}
                    <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3zm7-13h2.67A2.31 2.31 0 0 1 22 4v7a2.31 2.31 0 0 1-2.33 2H17"></path>
                    </svg>
                {:else}
                    <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                         stroke-width="1.5">
                        <path d="M9 18V5l12-2v13"></path>
                        <circle cx="6" cy="18" r="3"></circle>
                        <circle cx="18" cy="16" r="3"></circle>
                    </svg>
                {/if}
            </div>
            <div class="pl-info">
                <div class="pl-name">{pl.name}</div>
                <div class="pl-count">{pl.tracks.length} songs</div>
            </div>
            {#if !SYSTEM.has(pl.name)}
                <button class="delete-btn" title="Delete playlist"
                        on:click|stopPropagation={() => playlistToDelete = pl}>
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <polyline points="3 6 5 6 21 6"></polyline>
                        <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"></path>
                        <path d="M10 11v6M14 11v6"></path>
                        <path d="M9 6V4h6v2"></path>
                    </svg>
                </button>
            {/if}
        </div>
    {/each}
</div>

<!-- Delete confirm modal -->
{#if playlistToDelete}
    <!-- svelte-ignore a11y-click-events-have-key-events -->
    <!-- svelte-ignore a11y-no-static-element-interactions -->
    <div class="modal-backdrop" on:click={() => { if (!isDeleting) playlistToDelete = null; }}>
        <div class="modal" on:click|stopPropagation>
            <h3>Delete playlist?</h3>
            <p>"{playlistToDelete.name}" will be permanently deleted.</p>
            <div class="modal-actions">
                <button class="cancel-btn" on:click={() => playlistToDelete = null} disabled={isDeleting}>
                    Cancel
                </button>
                <button class="danger-btn" on:click={confirmDelete} disabled={isDeleting}>
                    {isDeleting ? 'Deleting…' : 'Delete'}
                </button>
            </div>
        </div>
    </div>
{/if}

<style>
    .library-actions {
        margin-bottom: 24px;
    }

    .create-row {
        display: flex;
        gap: 8px;
    }

    .create-row input {
        flex: 1;
    }

    .playlist-grid {
        display: flex;
        flex-direction: column;
        gap: 8px;
    }

    .pl-card {
        display: flex;
        align-items: center;
        gap: 16px;
        background: var(--bg-elevated);
        padding: 12px;
        border-radius: 8px;
        cursor: pointer;
        transition: background 0.15s, transform 0.15s;
    }

    .pl-card:hover {
        background: var(--bg-hover);
        transform: translateX(4px);
    }

    .pl-card:hover .delete-btn {
        opacity: 1;
    }

    .pl-art {
        width: 64px;
        height: 64px;
        border-radius: 6px;
        background: var(--bg-hover);
        display: flex;
        align-items: center;
        justify-content: center;
        color: var(--text-secondary);
        overflow: hidden;
        flex-shrink: 0;
    }

    .pl-art img {
        width: 100%;
        height: 100%;
        object-fit: cover;
    }

    .pl-info {
        flex: 1;
        overflow: hidden;
    }

    .pl-name {
        font-weight: 600;
        font-size: 16px;
        color: var(--text-primary);
        margin-bottom: 4px;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
    }

    .pl-count {
        font-size: 13px;
        color: var(--text-secondary);
    }

    .delete-btn {
        flex-shrink: 0;
        background: transparent;
        color: var(--text-secondary);
        padding: 8px;
        border-radius: 6px;
        display: flex;
        align-items: center;
        justify-content: center;
        opacity: 0;
        transition: opacity 0.15s, color 0.15s, background 0.15s;
    }

    .delete-btn:hover {
        color: #ef4444;
        background: rgba(239, 68, 68, 0.1);
    }

    @media (hover: none) {
        .delete-btn {
            opacity: 1;
        }
    }

    .modal-backdrop {
        position: fixed;
        inset: 0;
        background: rgba(0, 0, 0, 0.7);
        display: flex;
        align-items: center;
        justify-content: center;
        z-index: 1000;
    }

    .modal {
        background: var(--bg-surface);
        border: 1px solid var(--border-subtle);
        border-radius: 8px;
        padding: 28px 24px 20px;
        width: 340px;
        box-shadow: 0 24px 48px rgba(0, 0, 0, 0.6);
        display: flex;
        flex-direction: column;
        gap: 10px;
    }

    .modal h3 {
        font-size: 17px;
        font-weight: 700;
    }

    .modal p {
        font-size: 14px;
        color: var(--text-secondary);
        line-height: 1.4;
    }

    .modal-actions {
        display: flex;
        justify-content: flex-end;
        gap: 8px;
        margin-top: 8px;
    }

    .cancel-btn {
        background: transparent;
        color: var(--text-secondary);
        padding: 9px 16px;
        border-radius: 6px;
        font-size: 14px;
    }

    .cancel-btn:hover:not(:disabled) {
        color: var(--text-primary);
        background: var(--bg-hover);
    }

    .danger-btn {
        background: #ef4444;
        color: #fff;
        padding: 9px 20px;
        border-radius: 6px;
        font-size: 14px;
        font-weight: 600;
    }

    .danger-btn:hover:not(:disabled) {
        background: #dc2626;
    }

    .danger-btn:disabled,
    .cancel-btn:disabled {
        opacity: 0.45;
        cursor: not-allowed;
    }
</style>
