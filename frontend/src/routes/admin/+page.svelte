<script lang="ts">
    import {onMount} from 'svelte';
    import {goto} from '$app/navigation';
    import {apiRequest} from '$lib/utils/api';
    import {addToast} from '$lib/utils/toast';
    import type {AdminStats, User, AdminStatsResponse, TrackStat, DayStat} from '$lib/utils/types';

    let stats: AdminStats = {
        totalUsers: 0, totalPlays: 0, uniqueTracks: 0, totalPlaytimeMs: 0,
        topUsers: [], topTracks: [], topArtists: [], playsLast7Days: [],
        peakHour: 0, avgPlaysPerUser: 0
    };
    let pending: User[] = [];
    let allUsers: User[] = [];
    let maxPlays: number = 1;
    let maxArtistPlays: number = 1;
    let maxTrackPlays: number = 1;
    let maxDayCount: number = 1;

    async function load() {
        try {
            const data: AdminStatsResponse = await apiRequest('/api/admin/stats');
            stats = data.stats;
            pending = data.pending;
            if (stats.topUsers?.length > 0) maxPlays = Math.max(...stats.topUsers.map(u => u.playCount));
            if (stats.topArtists?.length > 0) maxArtistPlays = Math.max(...stats.topArtists.map(u => u.playCount));
            if (stats.topTracks?.length > 0) maxTrackPlays = Math.max(...stats.topTracks.map(t => t.playCount));
            if (stats.playsLast7Days?.length > 0) maxDayCount = Math.max(...stats.playsLast7Days.map(d => d.count), 1);

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

    function formatHour(h: number): string {
        if (h === 0) return '12 AM';
        if (h < 12) return `${h} AM`;
        if (h === 12) return '12 PM';
        return `${h - 12} PM`;
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
        <h3>Unique Tracks</h3>
        <div class="val">{stats.uniqueTracks}</div>
    </div>
    <div class="stat-card highlight">
        <h3>Total Playtime</h3>
        <div class="val">{formatTime(stats.totalPlaytimeMs)}</div>
    </div>
    <div class="stat-card">
        <h3>Avg Plays / User</h3>
        <div class="val">{stats.avgPlaysPerUser.toFixed(1)}</div>
    </div>
    <div class="stat-card">
        <h3>Peak Hour</h3>
        <div class="val">{formatHour(stats.peakHour)}</div>
    </div>
</div>

<!-- 7-day activity chart -->
<div class="section-card full-width" style="margin-bottom: 24px;">
    <h3>Activity — Last 7 Days</h3>
    {#if stats.playsLast7Days.length === 0}
        <p class="empty-state">No data yet.</p>
    {:else}
        <div class="day-chart">
            {#each stats.playsLast7Days as d}
                <div class="day-col">
                    <div class="day-bar-wrap">
                        <div class="day-bar-fill" style="height: {maxDayCount > 0 ? (d.count / maxDayCount) * 100 : 0}%">
                            {#if d.count > 0}
                                <span class="day-bar-label">{d.count}</span>
                            {/if}
                        </div>
                    </div>
                    <div class="day-label">{d.day}</div>
                </div>
            {/each}
        </div>
    {/if}
</div>

<div class="dashboard-bottom" style="margin-bottom: 24px;">
    <div class="section-card">
        <h3>Top Tracks</h3>
        {#if stats.topTracks.length === 0}
            <p class="empty-state">No listening history yet.</p>
        {:else}
            <div class="bar-chart">
                {#each stats.topTracks as t}
                    <div class="bar-row">
                        <div class="track-label">
                            <div class="track-title">{t.title}</div>
                            <div class="track-artist">{t.artist}</div>
                        </div>
                        <div class="bar-track">
                            <div class="bar-fill accent" style="width: {(t.playCount / maxTrackPlays) * 100}%"></div>
                        </div>
                        <span class="count">{t.playCount}</span>
                    </div>
                {/each}
            </div>
        {/if}
    </div>

    <div class="section-card">
        <h3>Top Artists</h3>
        {#if stats.topArtists.length === 0}
            <p class="empty-state">No listening history yet.</p>
        {:else}
            <div class="bar-chart">
                {#each stats.topArtists as a}
                    <div class="bar-row">
                        <span class="label">{a.username}</span>
                        <div class="bar-track">
                            <div class="bar-fill purple" style="width: {(a.playCount / maxArtistPlays) * 100}%"></div>
                        </div>
                        <span class="count">{a.playCount}</span>
                    </div>
                {/each}
            </div>
        {/if}
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
                        <div class="user-meta">
                            <div class="u-name">{u.username}<span class="role-tag">({u.role})</span></div>
                            <div class="u-id">ID: {u.id}</div>
                        </div>
                    </div>
                    <div class="action-buttons">
                        <button class="action-btn secondary"
                                on:click={() => resetPassword(u.id, u.username)}>
                            Reset Pwd
                        </button>
                        {#if u.role !== 'ADMIN'}
                            <button class="danger action-btn"
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

    .bar-fill.accent {
        background: var(--accent-color);
    }

    .bar-fill.purple {
        background: #a855f7;
    }

    .full-width {
        grid-column: 1 / -1;
    }

    .day-chart {
        display: flex;
        align-items: flex-end;
        gap: 12px;
        height: 120px;
        padding-top: 24px;
    }

    .day-col {
        flex: 1;
        display: flex;
        flex-direction: column;
        align-items: center;
        height: 100%;
        gap: 6px;
    }

    .day-bar-wrap {
        flex: 1;
        width: 100%;
        display: flex;
        align-items: flex-end;
        background: var(--bg-elevated);
        border-radius: 4px 4px 0 0;
        overflow: hidden;
    }

    .day-bar-fill {
        width: 100%;
        background: var(--accent-color);
        border-radius: 4px 4px 0 0;
        transition: height 0.8s cubic-bezier(0.4, 0, 0.2, 1);
        display: flex;
        align-items: flex-start;
        justify-content: center;
        min-height: 2px;
        position: relative;
    }

    .day-bar-label {
        font-size: 11px;
        font-weight: 700;
        color: #fff;
        position: absolute;
        top: 4px;
        opacity: 0.9;
    }

    .day-label {
        font-size: 11px;
        color: var(--text-secondary);
        white-space: nowrap;
    }

    .track-label {
        width: 120px;
        min-width: 120px;
        overflow: hidden;
    }

    .track-title {
        font-size: 13px;
        font-weight: 500;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        color: var(--text-primary);
    }

    .track-artist {
        font-size: 11px;
        color: var(--text-secondary);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
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
        min-width: 0;
    }

    .avatar {
        width: 36px;
        height: 36px;
        min-width: 36px;
        min-height: 36px;
        flex-shrink: 0;
        aspect-ratio: 1;
        background: var(--bg-hover);
        color: var(--text-primary);
        border-radius: 50%;
        overflow: hidden;
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

    .user-meta {
        min-width: 0;
    }

    .role-tag {
        font-size: 11px;
        color: var(--text-secondary);
        margin-left: 8px;
    }

    .u-id {
        font-size: 12px;
        color: var(--text-secondary);
        overflow-wrap: anywhere;
    }

    .action-buttons {
        display: flex;
        gap: 8px;
    }

    .action-btn {
        padding: 6px 12px;
        font-size: 12px;
        line-height: 1;
        transform: none;
    }

    .action-btn:hover {
        transform: none;
    }

    .action-btn:active {
        transform: none;
    }

    .action-btn.secondary {
        background: var(--bg-hover);
        color: var(--text-primary);
    }

    @media (max-width: 768px) {
        .dashboard-bottom {
            grid-template-columns: 1fr;
        }
    }
</style>