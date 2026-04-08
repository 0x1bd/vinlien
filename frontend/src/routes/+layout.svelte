<script lang="ts">
    import '../app.css';
    import {onMount} from 'svelte';
    import {goto} from '$app/navigation';
    import {page} from '$app/stores';
    import {user, userPlaylists, isSidebarOpen, queue, currentTrackIndex, isPlaying} from '$lib/utils/store';
    import {addToast, toasts} from '$lib/utils/toast';
    import {apiRequest} from '$lib/utils/api';
    import {audioManager} from '$lib/utils/AudioManager';
    import Player from '$lib/components/Player.svelte';
    import AddToPlaylistModal from '$lib/components/AddToPlaylistModal.svelte';

    let hasLoadedPlaylists = false;
    let isCreatingPlaylist = false;
    let newPlaylistName = "";
    let newPlaylistInput: HTMLInputElement;
    let dragOverId: string | null = null;

    let changingPassword = false;
    let newPasswordInput = "";

    $: if (!$user && $page.url.pathname !== '/login') {
        goto('/login');
    }

    async function loadPlaylists() {
        try {
            const res = await apiRequest('/api/playlists');
            if (res) $userPlaylists = res;
        } catch (e) {
        }
    }

    $: if ($user && !hasLoadedPlaylists) {
        loadPlaylists();
        hasLoadedPlaylists = true;
    }

    function logout() {
        apiRequest('/api/auth/logout', {method: 'POST'}).catch(() => {
        });
        $user = null;
        hasLoadedPlaylists = false;
        $userPlaylists = [];
        $queue = [];
        $currentTrackIndex = -1;
        $isPlaying = false;
        goto('/login', {replaceState: true});
    }

    function startCreatePlaylist() {
        isCreatingPlaylist = true;
        newPlaylistName = "";
        setTimeout(() => newPlaylistInput && newPlaylistInput.focus(), 10);
    }

    async function finishCreatePlaylist() {
        if (!isCreatingPlaylist) return;
        const name = newPlaylistName.trim();
        isCreatingPlaylist = false;
        if (name) {
            await apiRequest('/api/playlists', {method: 'POST', body: {name}});
            loadPlaylists();
        }
    }

    function handleDrop(e: DragEvent, playlistId: string) {
        dragOverId = null;
        if (!e.dataTransfer) return;
        const trackData = e.dataTransfer.getData('application/json');
        if (!trackData) return;
        apiRequest(`/api/playlists/${playlistId}/tracks`, {method: 'POST', body: JSON.parse(trackData)})
            .then(loadPlaylists);
    }

    async function submitNewPassword() {
        if (!newPasswordInput || newPasswordInput.trim().length < 4) {
            addToast("Password too short", "error");
            return;
        }
        changingPassword = true;
        try {
            await apiRequest('/api/auth/change-password', {
                method: 'POST',
                body: {newPassword: newPasswordInput.trim()}
            });
            $user = {...$user, requiresPasswordChange: false};
            addToast("Password updated successfully", "success");
        } catch (e) {
            addToast("Failed to update password", "error");
        } finally {
            changingPassword = false;
        }
    }

    onMount(() => {
        audioManager;
        const handleResize = () => $isSidebarOpen = window.innerWidth > 600;
        handleResize();
        window.addEventListener('resize', handleResize);
    });
</script>

<div class="toast-container">
    {#each $toasts as t (t.id)}
        <div class="toast {t.type}">{t.message}</div>
    {/each}
</div>

{#if !$user && $page.url.pathname === '/login'}
    <slot/>
{:else if $user}
    <div class="layout" class:sidebar-open={$isSidebarOpen}>
        <aside class="sidebar" class:collapsed={!$isSidebarOpen}>
            <div class="brand">
                <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                     stroke-linecap="round" stroke-linejoin="round" class="logo-icon">
                    <path d="M9 18V5l12-2v13"></path>
                    <circle cx="6" cy="18" r="3"></circle>
                    <circle cx="18" cy="16" r="3"></circle>
                </svg>
                <h1>Vinlien</h1>
            </div>

            <nav>
                <a href="/" class="nav-btn" class:active={$page.url.pathname === '/'}>
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"></path>
                    </svg>
                    Home
                </a>
                <a href="/settings" class="nav-btn" class:active={$page.url.pathname === '/settings'}>
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <circle cx="12" cy="12" r="3"></circle>
                        <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
                    </svg>
                    Settings
                </a>
                {#if $user.role === 'ADMIN'}
                    <a href="/admin" class="nav-btn" class:active={$page.url.pathname === '/admin'}>
                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                             stroke-width="2">
                            <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
                        </svg>
                        Admin
                    </a>
                {/if}
            </nav>

            <div class="sidebar-playlists">
                <div class="playlists-header">
                    <h3>Your Library</h3>
                    <button class="add-pl-btn" on:click={startCreatePlaylist}>+</button>
                </div>
                <div class="pl-list">
                    {#each $userPlaylists as pl}
                        <a href="/playlist/{pl.id}" class="pl-item"
                           class:active={$page.url.pathname === `/playlist/${pl.id}`}
                           class:drag-over={dragOverId === pl.id}
                           on:dragover|preventDefault on:dragenter={() => dragOverId = pl.id}
                           on:dragleave={() => dragOverId = null} on:drop={(e) => handleDrop(e, pl.id)}>

                            <div style="display: flex; align-items: center; gap: 8px;">
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
                                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                                         stroke-width="2">
                                        <line x1="8" y1="6" x2="21" y2="6"></line>
                                        <line x1="8" y1="12" x2="21" y2="12"></line>
                                        <line x1="8" y1="18" x2="21" y2="18"></line>
                                        <line x1="3" y1="6" x2="3.01" y2="6"></line>
                                        <line x1="3" y1="12" x2="3.01" y2="12"></line>
                                        <line x1="3" y1="18" x2="3.01" y2="18"></line>
                                    </svg>
                                {/if}
                                <span>{pl.name}</span>
                            </div>
                        </a>
                    {/each}
                    {#if isCreatingPlaylist}
                        <div class="pl-item creating">
                            <input type="text" class="inline-pl-input" bind:this={newPlaylistInput}
                                   bind:value={newPlaylistName} on:blur={finishCreatePlaylist}
                                   on:keydown={(e) => {if(e.key==='Enter') finishCreatePlaylist(); if(e.key==='Escape') isCreatingPlaylist=false;}}
                                   placeholder="Name..."/>
                        </div>
                    {/if}
                </div>
            </div>

            <div class="user-block">
                <div class="avatar">{$user.username[0].toUpperCase()}</div>
                <div class="user-info"><span class="value">{$user.username}</span></div>
                <button class="logout icon-btn" on:click={logout}>
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"/>
                    </svg>
                </button>
            </div>
        </aside>

        <main class="content">
            <div class="main-view-inner">
                <slot/>
            </div>
        </main>

        <!-- Mobile Bottom Nav -->
        <nav class="mobile-bottom-nav">
            <a href="/" class="mobile-nav-btn" class:active={$page.url.pathname === '/'}>
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"></path>
                </svg>
                <span>Home</span>
            </a>

            <a href="/library" class="mobile-nav-btn" class:active={$page.url.pathname.startsWith('/library')}>
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <line x1="8" y1="6" x2="21" y2="6"></line>
                    <line x1="8" y1="12" x2="21" y2="12"></line>
                    <line x1="8" y1="18" x2="21" y2="18"></line>
                    <line x1="3" y1="6" x2="3.01" y2="6"></line>
                    <line x1="3" y1="12" x2="3.01" y2="12"></line>
                    <line x1="3" y1="18" x2="3.01" y2="18"></line>
                </svg>
                <span>Library</span>
            </a>

            <a href="/settings" class="mobile-nav-btn" class:active={$page.url.pathname === '/settings'}>
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <circle cx="12" cy="12" r="3"></circle>
                    <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
                </svg>
                <span>Settings</span>
            </a>

            <button class="mobile-nav-btn" style="background: none; padding: 0;" on:click={logout}>
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"/>
                </svg>
                <span>Logout</span>
            </button>
        </nav>
    </div>

    <!-- Password Requirement Modal overlay -->
    {#if $user?.requiresPasswordChange}
        <div class="modal-backdrop force-password-modal">
            <div class="modal-content text-center">
                <div class="modal-header">
                    <h3>Action Required</h3>
                </div>
                <div class="modal-body">
                    <p style="margin-bottom: 24px; font-size: 14px; color: var(--text-secondary);">
                        Warning: Your password is currently set to the default value. For security, please update it
                        now.
                    </p>
                    <input type="password" bind:value={newPasswordInput} placeholder="New Password"
                           style="width: 100%; margin-bottom: 16px;"
                           on:keydown={e => e.key === 'Enter' && submitNewPassword()}/>

                    <div style="display: flex; gap: 12px; justify-content: flex-end;">
                        <button class="danger" on:click={logout}>Logout</button>
                        <button on:click={submitNewPassword} disabled={changingPassword}>
                            {changingPassword ? 'Updating...' : 'Update Password'}
                        </button>
                    </div>
                </div>
            </div>
        </div>
    {/if}

    <Player/>
    <AddToPlaylistModal/>
{/if}

<style>
    .layout {
        display: flex;
        height: 100vh;
        width: 100vw;
        overflow: hidden;
    }

    .sidebar {
        width: 260px;
        background: #000;
        display: flex;
        flex-direction: column;
        padding: 24px 0 0 0;
        z-index: 100;
        transition: 0.3s;
        flex-shrink: 0;
    }

    .sidebar.collapsed {
        width: 0;
        padding: 0;
        overflow: hidden;
    }

    .brand {
        display: flex;
        align-items: center;
        gap: 12px;
        margin-bottom: 32px;
        padding: 0 24px;
        white-space: nowrap;
    }

    .logo-icon {
        color: var(--text-primary);
    }

    .brand h1 {
        font-size: 20px;
        font-weight: 700;
    }

    nav {
        display: flex;
        flex-direction: column;
        gap: 8px;
        padding: 0 12px;
    }

    .nav-btn {
        display: flex;
        align-items: center;
        gap: 16px;
        color: var(--text-secondary);
        text-decoration: none;
        font-weight: 600;
        font-size: 14px;
        padding: 12px 16px;
        border-radius: 4px;
    }

    .nav-btn:hover {
        color: var(--text-primary);
    }

    .nav-btn.active {
        color: var(--text-primary);
        background: var(--bg-elevated);
    }

    .sidebar-playlists {
        flex: 1;
        display: flex;
        flex-direction: column;
        margin-top: 32px;
        overflow-y: auto;
    }

    .playlists-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 0 24px;
        margin-bottom: 12px;
    }

    .playlists-header h3 {
        font-size: 12px;
        color: var(--text-secondary);
        font-weight: 700;
        text-transform: uppercase;
    }

    .add-pl-btn {
        background: transparent;
        color: var(--text-secondary);
        font-size: 20px;
        padding: 0;
    }

    .pl-list {
        display: flex;
        flex-direction: column;
        gap: 4px;
        padding: 0 12px;
    }

    .pl-item {
        padding: 10px 16px;
        color: var(--text-secondary);
        text-decoration: none;
        font-size: 14px;
        font-weight: 500;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        border-radius: 4px;
    }

    .pl-item:hover {
        color: var(--text-primary);
    }

    .pl-item.active {
        color: var(--accent-color);
        background: rgba(37, 99, 235, 0.1);
    }

    .pl-item.drag-over {
        background: var(--bg-elevated);
        border: 1px dashed var(--text-secondary);
    }

    .inline-pl-input {
        width: 100%;
        padding: 8px;
        background: var(--bg-hover);
        color: #fff;
        border: 1px solid var(--border-subtle);
        border-radius: 4px;
    }

    .user-block {
        display: flex;
        align-items: center;
        gap: 12px;
        background: #000;
        padding: 16px 24px;
    }

    .avatar {
        width: 32px;
        height: 32px;
        border-radius: 50%;
        background: var(--bg-elevated);
        display: flex;
        align-items: center;
        justify-content: center;
        font-weight: 600;
        font-size: 14px;
        flex-shrink: 0;
    }

    .user-info {
        flex: 1;
        overflow: hidden;
        font-size: 14px;
        font-weight: 600;
        text-overflow: ellipsis;
    }

    .content {
        flex: 1;
        height: 100vh;
        overflow-y: auto;
        background: linear-gradient(180deg, var(--bg-surface) 0%, var(--bg-base) 100%);
    }

    .main-view-inner {
        padding: 40px 40px 130px;
        max-width: 1400px;
        margin: 0 auto;
    }

    .toast-container {
        position: fixed;
        top: 20px;
        right: 20px;
        z-index: 9999;
        display: flex;
        flex-direction: column;
        gap: 10px;
    }

    .toast {
        padding: 12px 20px;
        border-radius: 6px;
        font-size: 14px;
        font-weight: 500;
        color: #fff;
        box-shadow: 0 4px 12px rgba(0, 0, 0, 0.4);
        animation: slideIn 0.3s ease;
    }

    .toast.success {
        background: #10b981;
    }

    .toast.error {
        background: #ef4444;
    }

    .toast.info {
        background: #3b82f6;
    }

    @keyframes slideIn {
        from {
            transform: translateX(100%);
            opacity: 0;
        }
        to {
            transform: translateX(0);
            opacity: 1;
        }
    }

    .mobile-bottom-nav {
        display: none;
    }

    .force-password-modal {
        position: fixed;
        top: 0;
        left: 0;
        width: 100vw;
        height: 100vh;
        background: rgba(0, 0, 0, 0.85);
        display: flex;
        align-items: center;
        justify-content: center;
        z-index: 2000;
    }

    .modal-content {
        background: var(--bg-surface);
        border: 1px solid var(--danger-color);
        padding: 32px;
        border-radius: 8px;
        width: 400px;
        box-shadow: 0 24px 48px rgba(0, 0, 0, 0.6);
    }

    .modal-header h3 {
        margin-bottom: 8px;
    }

    @media (max-width: 600px) {
        .sidebar {
            display: none;
        }

        .main-view-inner {
            padding: 24px 16px 160px !important;
        }

        .mobile-bottom-nav {
            display: flex;
            flex-direction: row;
            gap: 0;
            padding: 0;
            position: fixed;
            bottom: 0;
            left: 0;
            right: 0;
            height: 65px;
            background: rgba(18, 18, 18, 0.95);
            backdrop-filter: blur(10px);
            border-top: 1px solid var(--border-subtle);
            z-index: 110;
            justify-content: space-around;
            align-items: center;
            padding-bottom: env(safe-area-inset-bottom);
        }

        .mobile-nav-btn {
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: 4px;
            color: var(--text-secondary);
            text-decoration: none;
            font-size: 10px;
            font-weight: 500;
        }

        .mobile-nav-btn.active {
            color: var(--text-primary);
        }

        .mobile-nav-btn svg {
            width: 24px;
            height: 24px;
        }
    }
</style>