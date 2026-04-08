<script lang="ts">
    import {page} from '$app/stores';
    import {queue, currentTrackIndex, isPlaying, metaProvidersOrder} from '$lib/utils/store';
    import {apiRequest} from '$lib/utils/api';
    import TrackRow from '$lib/components/TrackRow.svelte';
    import AlbumCard from '$lib/components/AlbumCard.svelte';
    import type {Track, Album, ArtistInfo} from '$lib/utils/types';

    $: artistName = decodeURIComponent($page.params.name);

    let tracks: Track[] = [];
    let albums: Album[] = [];
    let artistInfo: ArtistInfo | null = null;

    // Albums whose title matches a top track title are singles
    $: filteredAlbums = albums.filter(album =>
        !tracks.some(t =>
            t.title.toLowerCase() === album.title.toLowerCase() &&
            t.artist.toLowerCase() === album.artist.toLowerCase()
        )
    );
    let isExpanded = false;
    let isLoading = true;

    $: if (artistName) {
        isLoading = true;
        const providers = encodeURIComponent($metaProvidersOrder.join(','));

        Promise.all([
            apiRequest(`/api/search?q=${encodeURIComponent(artistName)}&providers=${providers}`).then((raw: any) => {
                tracks = raw.tracks.filter((t: Track) => t.artist.toLowerCase().includes(artistName.toLowerCase()) || t.title.toLowerCase().includes(artistName.toLowerCase()));
            }).catch(() => tracks = []),

            apiRequest(`/api/artist/${encodeURIComponent(artistName)}`).then(info => {
                artistInfo = info;
            }).catch(() => artistInfo = null),

            apiRequest(`/api/artist/${encodeURIComponent(artistName)}/albums`).then(res => {
                albums = res;
            }).catch(() => albums = [])
        ]).finally(() => {
            isLoading = false;
        });
    }

    function playSingleTrack(track: Track) {
        $queue = [track];
        $currentTrackIndex = 0;
        $isPlaying = true;
    }

    function playAll() {
        if (tracks.length === 0) return;
        $queue = [...tracks];
        $currentTrackIndex = 0;
        $isPlaying = true;
    }
</script>

{#if isLoading}
    <div class="artist-header">
        <div class="artist-avatar skeleton pulse"></div>
        <div class="artist-meta" style="width: 100%;">
            <div class="skeleton pulse" style="width: 200px; height: 36px; border-radius: 4px; margin-top: 10px;"></div>
            <div class="tags" style="margin-top: 16px;">
                <div class="skeleton pulse tag" style="width: 60px; height: 24px; color: transparent;">-</div>
                <div class="skeleton pulse tag" style="width: 80px; height: 24px; color: transparent;">-</div>
                <div class="skeleton pulse tag" style="width: 50px; height: 24px; color: transparent;">-</div>
            </div>
            <div class="bio" style="margin-top: 16px;">
                <div class="skeleton pulse"
                     style="width: 100%; height: 14px; border-radius: 2px; margin-bottom: 6px;"></div>
                <div class="skeleton pulse"
                     style="width: 90%; height: 14px; border-radius: 2px; margin-bottom: 6px;"></div>
                <div class="skeleton pulse" style="width: 80%; height: 14px; border-radius: 2px;"></div>
            </div>
        </div>
    </div>

    <div class="section-header">
        <div class="skeleton pulse" style="width: 120px; height: 24px; border-radius: 4px;"></div>
    </div>
    <div class="track-list">
        {#each Array(5) as _}
            <div class="skeleton pulse"
                 style="width: 100%; height: 60px; border-radius: 4px; margin-bottom: 4px;"></div>
        {/each}
    </div>

    <div class="section-header" style="margin-top: 48px;">
        <div class="skeleton pulse" style="width: 100px; height: 24px; border-radius: 4px;"></div>
    </div>
    <div class="album-grid">
        {#each Array(6) as _}
            <div class="album-item-wrapper">
                <div class="skeleton pulse"
                     style="width: 100%; aspect-ratio: 1; border-radius: 8px; margin-bottom: 12px;"></div>
                <div class="skeleton pulse"
                     style="width: 80%; height: 14px; border-radius: 2px; margin-bottom: 6px;"></div>
                <div class="skeleton pulse" style="width: 60%; height: 12px; border-radius: 2px;"></div>
            </div>
        {/each}
    </div>
{:else}
    <div class="artist-header">
        <div class="artist-avatar">
            {#if artistInfo?.imageUrl}
                <img src={artistInfo.imageUrl} alt={artistName} class="artist-photo"/>
            {:else}
                {artistName[0].toUpperCase()}
            {/if}
        </div>
        <div class="artist-meta">
            <h2 class="page-title">{artistName}</h2>
            {#if artistInfo?.tags && artistInfo.tags.length > 0}
                <div class="tags">
                    {#each artistInfo.tags as tag}
                        <span class="tag">{tag}</span>
                    {/each}
                </div>
            {/if}
            {#if artistInfo?.bio}
                <p class="bio" class:expanded={isExpanded}>
                    {@html artistInfo.bio}
                </p>
                {#if artistInfo.bio.length > 150}
                    <button class="read-more" on:click={() => isExpanded = !isExpanded}>
                        {isExpanded ? 'Show less' : 'Read more'}
                    </button>
                {/if}
            {/if}
        </div>
    </div>

    {#if tracks.length > 0}
        <div class="section-header">
            <p>Top Tracks</p>
            <button class="play-all-btn" on:click={playAll}>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M8 5v14l11-7z"/>
                </svg>
                Play All
            </button>
        </div>
        <div class="track-list">
            {#each tracks as track}
                <TrackRow {track} onPlay={() => playSingleTrack(track)}/>
            {/each}
        </div>
    {/if}

    {#if filteredAlbums.length > 0}
        <div class="section-header" style="margin-top: 48px;">
            <p>Albums</p>
        </div>
        <div class="album-grid">
            {#each filteredAlbums as album}
                <div class="album-item-wrapper">
                    <AlbumCard {album}/>
                </div>
            {/each}
        </div>
    {/if}
{/if}

<style>
    .artist-header {
        display: flex;
        gap: 32px;
        align-items: flex-start;
        margin-bottom: 48px;
        padding-bottom: 32px;
        border-bottom: 1px solid var(--border-subtle);
    }

    .artist-avatar {
        width: 150px;
        height: 150px;
        border-radius: 50%;
        background: linear-gradient(135deg, var(--bg-elevated), var(--accent-color));
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 64px;
        font-weight: 800;
        color: #fff;
        flex-shrink: 0;
        box-shadow: 0 16px 32px rgba(0, 0, 0, 0.4);
        overflow: hidden;
    }

    .artist-photo {
        width: 100%;
        height: 100%;
        object-fit: cover;
        border-radius: 50%;
    }

    .artist-meta {
        flex: 1;
        display: flex;
        flex-direction: column;
        gap: 16px;
    }

    .tags {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
    }

    .tag {
        background: var(--bg-elevated);
        color: var(--text-primary);
        font-size: 12px;
        padding: 4px 12px;
        border-radius: 16px;
        text-transform: capitalize;
    }

    .bio {
        color: var(--text-secondary);
        font-size: 14px;
        line-height: 1.6;
        display: -webkit-box;
        -webkit-line-clamp: 3;
        -webkit-box-orient: vertical;
        overflow: hidden;
    }

    .bio.expanded {
        display: block;
        -webkit-line-clamp: unset;
    }

    .read-more {
        background: transparent;
        color: var(--accent-color);
        padding: 0;
        font-size: 13px;
        font-weight: 600;
        text-align: left;
        width: fit-content;
    }

    .read-more:hover {
        background: transparent;
        color: #fff;
        transform: none;
        text-decoration: underline;
    }

    .section-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: 16px;
    }

    .section-header p {
        font-size: 20px;
        font-weight: 700;
        color: #fff;
        margin: 0;
    }

    .play-all-btn {
        display: flex;
        align-items: center;
        gap: 6px;
        padding: 8px 16px;
        font-size: 13px;
    }

    .track-list {
        display: flex;
        flex-direction: column;
        gap: 2px;
    }

    .album-grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
        gap: 24px;
    }

    @media (max-width: 768px) {
        .artist-header {
            flex-direction: column;
            align-items: center;
            text-align: center;
            gap: 24px;
        }

        .tags {
            justify-content: center;
        }

        .read-more {
            margin: 0 auto;
        }

        .album-grid {
            display: flex;
            overflow-x: auto;
            gap: 16px;
            padding-bottom: 12px;
            margin-bottom: -12px;
        }

        .album-item-wrapper {
            min-width: 160px;
            width: 160px;
        }

        .album-grid::-webkit-scrollbar {
            height: 0;
        }
    }

    .skeleton {
        background: var(--bg-hover, #2a2a2a);
        overflow: hidden;
        position: relative;
    }

    .pulse::after {
        content: '';
        position: absolute;
        top: 0;
        left: -100%;
        width: 50%;
        height: 100%;
        background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.05), transparent);
        animation: pulse-anim 1.5s infinite;
    }

    @keyframes pulse-anim {
        0% {
            left: -100%;
        }
        100% {
            left: 200%;
        }
    }
</style>