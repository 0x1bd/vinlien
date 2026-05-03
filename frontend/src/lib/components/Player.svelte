<script lang="ts">
    import { browser } from '$app/environment';
    import {
        currentTrack,
        isPlaying,
        userPlaylists,
        showQueuePanel,
        autoDownloadPlaylists,
        requireOnline,
        fetchSimilarTracksIfNeeded,
        isPlayerExpanded
    } from '$lib/utils/store';
    import { get } from 'svelte/store';
    import { apiRequest } from '$lib/utils/api';
    import { addToast } from '$lib/utils/toast';
    import { audioManager } from '$lib/utils/AudioManager';
    import { downloadTrack } from '$lib/utils/offlineAudio';
    import { page } from '$app/state';

    import QueuePanel from './QueuePanel.svelte';
    import MiniPlayer from './MiniPlayer.svelte';
    import MobileExpandedPlayer from './MobileExpandedPlayer.svelte';
    import DesktopExpandedPlayer from './DesktopExpandedPlayer.svelte';
    import TrackInfoPanel from './TrackInfoPanel.svelte';

    let innerWidth = 0;
    let lastPathname = '';
    let showTrackInfo = false;

    $: likedPlaylist    = $userPlaylists.find(p => p.name === 'Liked Songs');
    $: dislikedPlaylist = $userPlaylists.find(p => p.name === 'Disliked Songs');
    $: isLiked    = !!($currentTrack && likedPlaylist?.tracks.some(t => t.id === $currentTrack.id));
    $: isDisliked = !!($currentTrack && dislikedPlaylist?.tracks.some(t => t.id === $currentTrack.id));

    $: isMobileExpanded  = $isPlayerExpanded && innerWidth <= 600;
    $: isDesktopExpanded = $isPlayerExpanded && innerWidth > 600;

    $: if (isDesktopExpanded && $currentTrack) fetchSimilarTracksIfNeeded($currentTrack);

    $: if (!$currentTrack && $isPlayerExpanded) $isPlayerExpanded = false;

    $: if (browser) {
        const pathname = page.url.pathname;
        if (lastPathname && pathname !== lastPathname && $isPlayerExpanded) {
            $isPlayerExpanded = false;
        }
        lastPathname = pathname;
    }

    async function toggleLike() {
        if (!$currentTrack) return;
        if (!requireOnline('Cannot like songs while offline')) return;
        const prev = isLiked;
        isLiked = !isLiked;
        if (isLiked) isDisliked = false;
        addToast(isLiked ? 'Added to Liked Songs' : 'Removed from Liked Songs', 'success');
        try {
            await apiRequest('/api/playlists/liked/toggle', { method: 'POST', body: $currentTrack });
            const res = await apiRequest('/api/playlists');
            if (res) $userPlaylists = res;
            if (isLiked) {
                const id = $userPlaylists.find(p => p.name === 'Liked Songs')?.id;
                if (id && get(autoDownloadPlaylists).includes(id)) downloadTrack($currentTrack).catch(() => {});
            }
        } catch { isLiked = prev; }
    }

    async function toggleDislike() {
        if (!$currentTrack) return;
        if (!requireOnline('Cannot dislike songs while offline')) return;
        const prev = isDisliked;
        isDisliked = !isDisliked;
        if (isDisliked) isLiked = false;
        addToast(isDisliked ? 'Will not recommend this song' : 'Removed from Dislikes', 'info');
        try {
            await apiRequest('/api/playlists/disliked/toggle', { method: 'POST', body: $currentTrack });
            const res = await apiRequest('/api/playlists');
            if (res) $userPlaylists = res;
            if (isDisliked) {
                const id = $userPlaylists.find(p => p.name === 'Disliked Songs')?.id;
                if (id && get(autoDownloadPlaylists).includes(id)) downloadTrack($currentTrack).catch(() => {});
                if ($isPlaying) await audioManager.playNext(true);
            }
        } catch { isDisliked = prev; }
    }

    function toggleInfo() {
        showTrackInfo = !showTrackInfo;
        if (showTrackInfo) $showQueuePanel = false;
    }
</script>

<svelte:window bind:innerWidth />

<QueuePanel />

{#if isDesktopExpanded}
    <DesktopExpandedPlayer />
{/if}

{#if isMobileExpanded}
    <MobileExpandedPlayer
        {isLiked}
        {isDisliked}
        onToggleLike={toggleLike}
        onToggleDislike={toggleDislike}
    />
{:else}
    <div class="player-wrapper">
        <MiniPlayer
            {isLiked}
            {isDisliked}
            {isDesktopExpanded}
            {showTrackInfo}
            onToggleLike={toggleLike}
            onToggleDislike={toggleDislike}
            onToggleInfo={toggleInfo}
        />
    </div>
{/if}

{#if showTrackInfo && $currentTrack}
    <TrackInfoPanel track={$currentTrack} onClose={() => showTrackInfo = false} />
{/if}

<style>
    .player-wrapper {
        position: fixed;
        bottom: 0;
        left: 260px;
        right: 0;
        z-index: 100;
        background: var(--bg-sidebar);
        border-top: 1px solid var(--border-subtle);
        transition: 0.3s;
    }

    @media (max-width: 600px) {
        .player-wrapper {
            left: 0;
            bottom: 65px;
            background: var(--bg-elevated);
            border-radius: 8px;
            margin: 0 8px 8px;
            border: none;
            box-shadow: 0 8px 24px rgba(0, 0, 0, 0.6);
        }
    }
</style>
