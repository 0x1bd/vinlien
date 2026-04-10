<script lang="ts">
    import {onDestroy, onMount} from 'svelte';
    import {goto} from '$app/navigation';
    import {apiRequest} from '$lib/utils/api';
    import {queue, currentTrackIndex, isPlaying} from '$lib/utils/store';
    import type {Track, HomeFeed, SearchResponse} from '$lib/utils/types';
    import TrackCard from '$lib/components/TrackCard.svelte';
    import TrackRow from '$lib/components/TrackRow.svelte';
    import AlbumCard from '$lib/components/AlbumCard.svelte';

    let feed: HomeFeed = {recentlyPlayed: [], listenAgain: [], forgottenFavorites: [], artists: []};
    let quickPicks: Track[] = [];

    let query = '';
    let searchResults: SearchResponse = {tracks: [], albums: []};
    let uniqueArtists: string[] = [];

    let isSearching = false;
    let isSearchOpen = false;
    let searchContainer: HTMLElement;
    let debounceTimer: ReturnType<typeof setTimeout>;
    let eventSource: EventSource | null = null;

    function closeEventSource() {
        eventSource?.close();
        eventSource = null;
    }

    function computeUniqueArtists(results: SearchResponse): string[] {
        return Array.from(new Set([
            ...results.tracks.flatMap((t: any) => t.artists as string[]),
            ...results.albums.map((a: any) => a.artist as string)
        ])).slice(0, 5);
    }

    function handleInput() {
        clearTimeout(debounceTimer);
        closeEventSource();

        if (!query.trim()) {
            searchResults = {tracks: [], albums: []};
            uniqueArtists = [];
            isSearching = false;
            isSearchOpen = false;
            return;
        }

        isSearchOpen = true;
        isSearching = true;
        searchResults = {tracks: [], albums: []};

        debounceTimer = setTimeout(() => {
            eventSource = new EventSource(
                `/api/search/stream?q=${encodeURIComponent(query)}`,
                {withCredentials: true}
            );

            eventSource.onmessage = (e) => {
                const data = JSON.parse(e.data) as SearchResponse;
                if (data.tracks.length > 0 || data.albums.length > 0) {
                    searchResults = data;
                    uniqueArtists = computeUniqueArtists(data);
                    isSearching = false;
                }
            };

            eventSource.addEventListener('done', () => {
                closeEventSource();
                isSearching = false;
            });

            eventSource.onerror = () => {
                closeEventSource();
                isSearching = false;
            };
        }, 500);
    }

    onDestroy(() => {
        closeEventSource();
        clearTimeout(debounceTimer);
    });

    function playTrack(track: Track) {
        $queue = [track];
        $currentTrackIndex = 0;
        $isPlaying = true;
        isSearchOpen = false;
    }

    function handleOutsideClick(event: MouseEvent) {
        if (isSearchOpen && searchContainer && !searchContainer.contains(event.target as Node)) {
            isSearchOpen = false;
        }
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

<svelte:window on:click={handleOutsideClick}/>

<div class="search-module" bind:this={searchContainer}>
    <div class="search-input-wrapper">
        <input
                type="text"
                bind:value={query}
                placeholder="What do you want to play?"
                on:input={handleInput}
                on:focus={() => { if (query.trim()) isSearchOpen = true; }}
        />
    </div>

    {#if isSearchOpen && query.trim()}
        <div class="search-results open">
            {#if isSearching}
                {#each Array(4) as _}
                    <div class="skeleton-row">
                        <div class="skeleton-img"></div>
                        <div class="skeleton-info">
                            <div class="skeleton-title"></div>
                            <div class="skeleton-artist"></div>
                        </div>
                    </div>
                {/each}
            {:else if searchResults.tracks.length > 0 || searchResults.albums.length > 0}

                {#if uniqueArtists.length > 0}
                    <div class="search-section-title">Artists</div>
                    <div class="artist-pills">
                        {#each uniqueArtists as artist}
                            <!-- svelte-ignore a11y-click-events-have-key-events -->
                            <!-- svelte-ignore a11y-no-static-element-interactions -->
                            <div class="artist-pill"
                                 on:click={() => { isSearchOpen = false; goto(`/artist/${encodeURIComponent(artist)}`); }}>
                                <div class="artist-pill-avatar">{artist[0].toUpperCase()}</div>
                                <span>{artist}</span>
                            </div>
                        {/each}
                    </div>
                {/if}

                {#if searchResults.tracks.length > 0}
                    <div class="search-section-title" style="margin-top: 16px;">Tracks</div>
                    {#each searchResults.tracks as track}
                        <TrackRow {track} onPlay={() => playTrack(track)}/>
                    {/each}
                {/if}

                {#if searchResults.albums.length > 0}
                    <div class="search-section-title" style="margin-top: 16px;">Albums</div>
                    <div class="album-scroll">
                        {#each searchResults.albums as album}
                            <div style="min-width: 150px; width: 150px;">
                                <AlbumCard {album}/>
                            </div>
                        {/each}
                    </div>
                {/if}

            {:else}
                <div class="no-results">No results found for "{query}"</div>
            {/if}
        </div>
    {/if}
</div>

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
    .search-module {
        position: relative;
        margin-bottom: 48px;
        max-width: 600px;
        z-index: 50;
    }

    .search-input-wrapper input {
        width: 100%;
        padding: 16px;
        font-size: 16px;
        border-radius: 32px;
    }

    .search-results {
        position: absolute;
        top: calc(100% + 8px);
        width: 100%;
        background: var(--bg-elevated);
        border-radius: 8px;
        max-height: 600px;
        overflow-y: auto;
        padding: 16px;
        box-shadow: 0 16px 32px rgba(0, 0, 0, 0.4);
    }

    .search-section-title {
        font-size: 14px;
        font-weight: 700;
        text-transform: uppercase;
        color: var(--text-secondary);
        margin-bottom: 8px;
        padding-left: 8px;
    }

    .artist-pills {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
        margin-bottom: 8px;
        padding-left: 8px;
    }

    .artist-pill {
        display: flex;
        align-items: center;
        gap: 8px;
        background: var(--bg-hover);
        padding: 6px 12px 6px 6px;
        border-radius: 32px;
        cursor: pointer;
        transition: 0.2s;
    }

    .artist-pill:hover {
        background: var(--border-subtle);
        transform: scale(1.02);
    }

    .artist-pill-avatar {
        width: 24px;
        height: 24px;
        background: var(--accent-color);
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 12px;
        font-weight: 700;
        color: #fff;
    }

    .album-scroll {
        display: flex;
        gap: 12px;
        overflow-x: auto;
        padding-bottom: 8px;
        margin-bottom: -8px;
    }

    .album-scroll::-webkit-scrollbar {
        height: 6px;
    }

    .no-results {
        padding: 16px;
        color: var(--text-secondary);
        text-align: center;
        font-size: 14px;
    }

    .skeleton-row {
        display: flex;
        align-items: center;
        gap: 16px;
        padding: 8px 16px;
        border-radius: 6px;
    }

    .skeleton-img {
        width: 40px;
        height: 40px;
        border-radius: 4px;
        background: var(--bg-hover);
        animation: pulse 1.5s infinite ease-in-out;
    }

    .skeleton-info {
        flex: 1;
        display: flex;
        flex-direction: column;
        gap: 8px;
    }

    .skeleton-title {
        width: 60%;
        height: 14px;
        background: var(--bg-hover);
        border-radius: 4px;
        animation: pulse 1.5s infinite ease-in-out;
    }

    .skeleton-artist {
        width: 40%;
        height: 12px;
        background: var(--bg-hover);
        border-radius: 4px;
        animation: pulse 1.5s infinite ease-in-out;
    }

    @keyframes pulse {
        0% {
            opacity: 0.5;
        }
        50% {
            opacity: 1;
        }
        100% {
            opacity: 0.5;
        }
    }

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