<script lang="ts">
    import {onMount} from 'svelte';
    import {apiRequest} from '$lib/utils/api';
    import {queue, currentTrackIndex, isPlaying} from '$lib/utils/store';
    import type {Track, HomeFeed} from '$lib/utils/types';
    import TrackCard from '$lib/components/TrackCard.svelte';

    let feed: HomeFeed = {recentlyPlayed: [], listenAgain: [], forgottenFavorites: [], artists: []};
    let quickPicks: Track[] = [];

    function playTrack(track: Track) {
        $queue = [track];
        $currentTrackIndex = 0;
        $isPlaying = true;
    }

    onMount(async () => {
        try {
            feed = await apiRequest('/api/home/feed');
        } catch (e) {
        }
        try {
            quickPicks = await apiRequest('/api/home/trending');
        } catch (e) {
        }
    });
</script>

{#if feed.recentlyPlayed.length > 0}
    <div class="header"><h2 class="page-title">Recently Played</h2></div>
    <div class="grid">
        {#each feed.recentlyPlayed.slice(0, 4) as track}
            <TrackCard {track} onPlay={() => playTrack(track)}/>
        {/each}
    </div>
{/if}

{#if quickPicks.length > 0}
    <div class="header" style="margin-top: 48px;"><h2 class="page-title">Quick Picks</h2></div>
    <div class="grid">
        {#each quickPicks.slice(0, 4) as track}
            <TrackCard {track} onPlay={() => playTrack(track)}/>
        {/each}
    </div>
{/if}

<style>
    .grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
        gap: 24px;
    }

    .grid > :global(*) {
        min-width: 0;
    }

    @media (max-width: 600px) {
        .grid {
            grid-template-columns: repeat(2, 1fr);
            gap: 12px;
        }
    }
</style>
