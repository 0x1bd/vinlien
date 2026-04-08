<script lang="ts">
    import {userPlaylists, trackToAdd} from '$lib/utils/store';
    import {addToast} from '$lib/utils/toast';
    import {apiRequest} from '$lib/utils/api';

    let newPlaylistName = '';
    let isCreating = false;

    async function loadPlaylists() {
        try {
            const res = await apiRequest('/api/playlists');
            if (res) $userPlaylists = res;
        } catch (e) {
        }
    }

    async function addToPlaylist(playlistId: string) {
        if (!$trackToAdd) return;
        try {
            await apiRequest(`/api/playlists/${playlistId}/tracks`, {
                method: 'POST',
                body: $trackToAdd
            });
            addToast(`Added to playlist`, 'success');
        } catch (e) {
        }
        $trackToAdd = null;
    }

    async function createAndAdd() {
        if (!newPlaylistName.trim() || !$trackToAdd) return;
        isCreating = true;

        try {
            const newPl = await apiRequest('/api/playlists', {
                method: 'POST',
                body: {name: newPlaylistName.trim()}
            });
            await addToPlaylist(newPl.id);
            newPlaylistName = '';
        } catch (e) {
        }
        isCreating = false;
    }

    function handleKeydown(e: KeyboardEvent) {
        if (e.key === 'Escape') $trackToAdd = null;
    }

    $: if ($trackToAdd) loadPlaylists();
</script>

<svelte:window on:keydown={handleKeydown}/>

{#if $trackToAdd}
    <!-- svelte-ignore a11y-click-events-have-key-events -->
    <!-- svelte-ignore a11y-no-static-element-interactions -->
    <div class="modal-backdrop" on:click={() => $trackToAdd = null}>
        <div class="modal-content" on:click|stopPropagation>
            <div class="modal-header">
                <h3>Add to Playlist</h3>
                <button class="icon-btn" on:click={() => $trackToAdd = null}>
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <line x1="18" y1="6" x2="6" y2="18"></line>
                        <line x1="6" y1="6" x2="18" y2="18"></line>
                    </svg>
                </button>
            </div>

            <div class="track-preview">
                <img src={$trackToAdd.artworkUrl} alt="art">
                <div>
                    <div class="title">{$trackToAdd.title}</div>
                    <div class="artist">{$trackToAdd.artist}</div>
                </div>
            </div>

            <div class="playlist-list">
                {#each $userPlaylists as pl}
                    <button class="playlist-btn" on:click={() => addToPlaylist(pl.id)}>
                        <div class="pl-avatar">
                            {#if pl.name === 'Liked Songs'}
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
                                    <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"></path>
                                </svg>
                            {:else if pl.name === 'Disliked Songs'}
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                                     stroke-width="2">
                                    <path d="M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3zm7-13h2.67A2.31 2.31 0 0 1 22 4v7a2.31 2.31 0 0 1-2.33 2H17"></path>
                                </svg>
                            {:else}
                                {pl.name[0]?.toUpperCase()}
                            {/if}
                        </div>
                        <span class="pl-name">{pl.name}</span>
                    </button>
                {/each}
            </div>

            <div class="create-pl-section">
                <input type="text" bind:value={newPlaylistName} placeholder="New playlist name..."
                       on:keydown={(e) => e.key === 'Enter' && createAndAdd()}/>
                <button on:click={createAndAdd} disabled={isCreating}>Create</button>
            </div>
        </div>
    </div>
{/if}

<style>
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
        padding: 16px;
        box-sizing: border-box;
    }

    .modal-content {
        background: var(--bg-surface);
        border: 1px solid var(--border-subtle);
        padding: 24px;
        border-radius: 8px;
        width: 100%;
        max-width: 400px;
        display: flex;
        flex-direction: column;
        gap: 16px;
        box-shadow: 0 24px 48px rgba(0, 0, 0, 0.6);
        box-sizing: border-box;
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

    .track-preview {
        display: flex;
        gap: 16px;
        align-items: center;
        background: var(--bg-elevated);
        padding: 12px;
        border-radius: 6px;
    }

    .track-preview img {
        width: 48px;
        height: 48px;
        border-radius: 4px;
    }

    .title {
        font-weight: 600;
        font-size: 14px;
    }

    .artist {
        color: var(--text-secondary);
        font-size: 13px;
    }

    .playlist-list {
        display: flex;
        flex-direction: column;
        gap: 4px;
        max-height: 200px;
        overflow-y: auto;
    }

    .playlist-btn {
        background: transparent;
        color: var(--text-primary);
        padding: 8px 12px;
        border-radius: 4px;
        display: flex;
        align-items: center;
        justify-content: flex-start;
        gap: 12px;
        font-weight: 500;
    }

    .playlist-btn:hover {
        background: var(--bg-hover);
        transform: none;
    }

    .pl-avatar {
        width: 32px;
        height: 32px;
        border-radius: 4px;
        background: var(--bg-elevated);
        color: var(--text-primary);
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 12px;
        font-weight: 600;
    }

    .create-pl-section {
        display: flex;
        gap: 12px;
        margin-top: 8px;
        border-top: 1px solid var(--border-subtle);
        padding-top: 16px;
    }

    .create-pl-section input {
        flex: 1;
        min-width: 0;
    }

    .create-pl-section button {
        flex-shrink: 0;
    }
</style>