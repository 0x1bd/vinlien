<script lang="ts">
    import {onMount} from 'svelte';
    import {apiRequest} from '$lib/utils/api';
    import {queue, currentTrackIndex, isPlaying} from '$lib/utils/store';
    import type {Track, HomeFeed, RecResult, RadioResponse} from '$lib/utils/types';
    import TrackCard from '$lib/components/TrackCard.svelte';

    let feed: HomeFeed = {recentlyPlayed: [], listenAgain: [], forgottenFavorites: [], artists: []};
    let quickPicks: Track[] = [];
    let recs: RecResult[] = [];

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
            const trending: Track[] = await apiRequest('/api/home/trending');
            quickPicks = trending.sort(() => Math.random() - 0.5);
        } catch (e) {
        }

        if (feed.recentlyPlayed.length > 0) {
            try {
                const seenIds = new Set<string>();
                const pool: Track[] = [];
                for (const t of [...feed.listenAgain, ...feed.recentlyPlayed]) {
                    if (!seenIds.has(t.id)) { seenIds.add(t.id); pool.push(t); }
                }
                for (let i = pool.length - 1; i > 0; i--) {
                    const j = Math.floor(Math.random() * (i + 1));
                    [pool[i], pool[j]] = [pool[j], pool[i]];
                }
                const seeds: Track[] = [];
                const seenArtists = new Set<string>();
                for (const t of pool) {
                    const a = t.artist.toLowerCase().trim();
                    if (!seenArtists.has(a)) { seenArtists.add(a); seeds.push(t); }
                    if (seeds.length >= 4) break;
                }
                const [seedTrack, ...additionalSeeds] = seeds;
                const radioRes: RadioResponse = await apiRequest('/api/radio', {
                    method: 'POST',
                    body: {
                        seedTrack,
                        additionalSeeds,
                        queue: [],
                        tracksPlayedInSession: 0,
                        sessionArtists: [],
                        queueSize: 4
                    }
                });
                recs = radioRes.tracks;
            } catch (e) {
            }
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

{#if recs.length > 0}
    <div class="header" style="margin-top: 48px;"><h2 class="page-title">Recommended For You</h2></div>
    <div class="grid">
        {#each recs as rec}
            <TrackCard track={rec.track} subtitle={rec.reason} onPlay={() => playTrack(rec.track)}/>
        {/each}
    </div>
{/if}

{#if feed.listenAgain.length > 0}
    <div class="header" style="margin-top: 48px;"><h2 class="page-title">Listen Again</h2></div>
    <div class="grid">
        {#each feed.listenAgain.slice(0, 4) as track}
            <TrackCard {track} onPlay={() => playTrack(track)}/>
        {/each}
    </div>
{/if}

{#if feed.forgottenFavorites.length > 0}
    <div class="header" style="margin-top: 48px;"><h2 class="page-title">Forgotten Favorites</h2></div>
    <div class="grid">
        {#each feed.forgottenFavorites.slice(0, 4) as track}
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
