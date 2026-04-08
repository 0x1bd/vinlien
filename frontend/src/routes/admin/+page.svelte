<script lang="ts">
    import {onMount} from 'svelte';
    import {goto} from '$app/navigation';
    import {apiRequest} from '$lib/utils/api';
    import {addToast} from '$lib/utils/toast';
    import type {AdminStats, User, AdminStatsResponse} from '$lib/utils/types';

    let stats: AdminStats = {totalUsers: 0, totalPlays: 0, uniqueTracks: 0, totalPlaytimeMs: 0, topUsers: []};
    let pending: User[] = [];
    let allUsers: User[] = [];
    let maxPlays: number = 1;

    async function load() {
        try {
            const data: AdminStatsResponse = await apiRequest('/api/admin/stats');
            stats = data.stats;
            pending = data.pending;
            if (stats.topUsers && stats.topUsers.length > 0) {
                maxPlays = Math.max(...stats.topUsers.map(u => u.playCount));
            }

            allUsers = await apiRequest('/api/admin/users');
        } catch (e) {
            goto('/', {replaceState: true});
        }
    }

    async function approve(id: string) {
        await apiRequest(`/api/admin/approve/${id}`, {method: 'POST'});
        load();
        addToast("User approved", "success");
    }

    async function resetPassword(id: string, username: string) {
        const newPassword = window.prompt(`Enter a new password for ${username}:`);
        if (!newPassword || newPassword.trim().length < 4) {
            if (newPassword !== null) addToast("Password too short or cancelled", "error");
            return;
        }

        try {
            await apiRequest(`/api/admin/users/${id}/password`, {
                method: 'POST',
                body: {newPassword: newPassword.trim()}
            });
            addToast(`Password updated for ${username}`, "success");
        } catch (e) {
            addToast("Failed to reset password", "error");
        }
    }

    async function deleteUser(id: string, username: string) {
        if (!window.confirm(`Are you sure you want to permanently delete user ${username}?`)) return;

        try {
            await apiRequest(`/api/admin/users/${id}`, {method: 'DELETE'});
            addToast(`User ${username} deleted`, "success");
            load();
        } catch (e: any) {
            addToast(e.message || "Failed to delete user", "error");
        }
    }

    async function clearCaches() {
        try {
            await apiRequest('/api/admin/cache/clear', {method: 'POST'});
            addToast("Search & Trending caches cleared successfully", "success");
        } catch (e) {
            addToast("Failed to clear caches", "error");
        }
    }

    function formatTime(ms: number): string {
        const hours = Math.floor(ms / (1000 * 60 * 60));
        const minutes = Math.floor((ms % (1000 * 60 * 60)) / (1000 * 60));
        return `${hours}h ${minutes}m`;
    }

    onMount(load);
</script>

<div class="header">
    <h2 class="page-title">Admin Dashboard</h2>
</div>

<div class="stats-grid">
    <div class="stat-card">
        <h3>Total Users</h3>
        <div class="val">{stats.totalUsers}</div>
    </div>
    <div class="stat-card">
        <h3>Total Plays</h3>
        <div class="val">{stats.totalPlays}</div>
    </div>
    <div class="stat-card">
        <h3>Cached Tracks</h3>
        <div class="val">{stats.uniqueTracks}</div>
    </div>
    <div class="stat-card highlight">
        <h3>Total Playtime</h3>
        <div class="val">{formatTime(stats.totalPlaytimeMs)}</div>
    </div>
</div>

<div class="dashboard-bottom">
    <div class="section-card">
        <h3>Top Listeners</h3>
        {#if stats.topUsers.length === 0}
            <p class="empty-state">No listening history yet.</p>
        {:else}
            <div class="bar-chart">
                {#each stats.topUsers as u}
                    <div class="bar-row">
                        <span class="label">{u.username}</span>
                        <div class="bar-track">
                            <div class="bar-fill" style="width: {(u.playCount / maxPlays) * 100}%"></div>
                        </div>
                        <span class="count">{u.playCount}</span>
                    </div>
                {/each}
            </div>
        {/if}
    </div>

    <div class="section-card">
        <h3>Pending Approvals</h3>
        <div class="list">
            {#if pending.length === 0}
                <p class="empty-state">No users pending approval.</p>
            {/if}
            {#each pending as p}
                <div class="approval-card">
                    <div class="user-info">
                        <div class="avatar">{p.username[0].toUpperCase()}</div>
                        <div>
                            <div class="u-name">{p.username}</div>
                            <div class="u-id">ID: {p.id}</div>
                        </div>
                    </div>
                    <button class="approve-btn" on:click={() => approve(p.id)}>Approve</button>
                </div>
            {/each}
        </div>
    </div>
</div>

<div class="dashboard-bottom" style="margin-top: 24px;">
    <div class="section-card">
        <h3>Users Management</h3>
        <div class="list">
            {#if allUsers.length === 0}
                <p class="empty-state">No users loaded.</p>
            {/if}
            {#each allUsers as u}
                <div class="approval-card">
                    <div class="user-info">
                        <div class="avatar" class:admin-avatar={u.role === 'ADMIN'}>
                            {u.username[0].toUpperCase()}
                        </div>
                        <div>
                            <div class="u-name">{u.username} <span
                                    style="font-size: 11px; color: var(--text-secondary); margin-left: 8px;">({u.role}
                                )</span></div>
                            <div class="u-id">ID: {u.id}</div>
                        </div>
                    </div>
                    <div style="display: flex; gap: 8px;">
                        <button style="padding: 6px 12px; font-size: 12px; background: var(--bg-hover); color: var(--text-primary);"
                                on:click={() => resetPassword(u.id, u.username)}>
                            Reset Pwd
                        </button>
                        {#if u.role !== 'ADMIN'}
                            <button class="danger" style="padding: 6px 12px; font-size: 12px;"
                                    on:click={() => deleteUser(u.id, u.username)}>
                                Delete
                            </button>
                        {/if}
                    </div>
                </div>
            {/each}
        </div>
    </div>

    <div class="section-card">
        <h3>System Actions</h3>
        <p class="empty-state" style="margin-bottom: 16px;">
            Clear the in-memory cache for search results and trending items to fetch fresh data from providers
            immediately.
        </p>
        <button class="danger" style="width: fit-content;" on:click={clearCaches}>Clear Search/Trending Cache</button>
    </div>
</div>

<style>
    .stats-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
        gap: 24px;
        margin-bottom: 32px;
    }

    .stat-card {
        background: var(--bg-elevated);
        border: 1px solid var(--border-subtle);
        padding: 24px;
        border-radius: 8px;
        display: flex;
        flex-direction: column;
    }

    .stat-card.highlight {
        background: var(--bg-hover);
    }

    .stat-card h3 {
        color: var(--text-secondary);
        font-size: 13px;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        margin-bottom: 8px;
        font-weight: 600;
    }

    .val {
        font-size: 32px;
        font-weight: 700;
        color: var(--text-primary);
    }

    .dashboard-bottom {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 24px;
    }

    .section-card {
        background: var(--bg-surface);
        border: 1px solid var(--border-subtle);
        border-radius: 8px;
        padding: 24px;
    }

    .section-card h3 {
        font-size: 18px;
        font-weight: 700;
        margin-bottom: 24px;
    }

    .empty-state {
        color: var(--text-secondary);
        font-size: 14px;
    }

    .bar-chart {
        display: flex;
        flex-direction: column;
        gap: 16px;
    }

    .bar-row {
        display: flex;
        align-items: center;
        gap: 16px;
    }

    .label {
        width: 80px;
        font-size: 14px;
        font-weight: 500;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
    }

    .bar-track {
        flex: 1;
        height: 6px;
        background: var(--bg-elevated);
        border-radius: 3px;
        overflow: hidden;
    }

    .bar-fill {
        height: 100%;
        background: var(--text-primary);
        border-radius: 3px;
        transition: width 1s cubic-bezier(0.4, 0, 0.2, 1);
    }

    .count {
        width: 40px;
        text-align: right;
        font-size: 13px;
        color: var(--text-secondary);
    }

    .list {
        display: flex;
        flex-direction: column;
        gap: 12px;
    }

    .approval-card {
        display: flex;
        justify-content: space-between;
        align-items: center;
        background: var(--bg-elevated);
        padding: 12px 16px;
        border-radius: 6px;
    }

    .user-info {
        display: flex;
        align-items: center;
        gap: 12px;
    }

    .avatar {
        width: 36px;
        height: 36px;
        background: var(--bg-hover);
        color: var(--text-primary);
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        font-weight: 600;
    }

    .admin-avatar {
        background: var(--accent-color);
        color: #fff;
    }

    .u-name {
        font-weight: 600;
        font-size: 14px;
    }

    .u-id {
        font-size: 12px;
        color: var(--text-secondary);
    }

    @media (max-width: 768px) {
        .dashboard-bottom {
            grid-template-columns: 1fr;
        }
    }
</style>