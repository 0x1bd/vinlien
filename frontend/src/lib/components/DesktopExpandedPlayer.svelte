<script lang="ts">
    import { currentTrack, similarTracks, isFetchingSimilar, queue, currentTrackIndex } from '$lib/utils/store';
    import ArtworkImage from './ArtworkImage.svelte';
    import QueuePanel from './QueuePanel.svelte';
    import { fade, fly } from 'svelte/transition';
    import { cubicOut } from 'svelte/easing';

    let isTransitioningOut = false;

    async function handleSimilarClick(track: any) {
        if (isTransitioningOut) return;
        isTransitioningOut = true;

        await new Promise(resolve => setTimeout(resolve, 300));

        $queue =[...$queue, track];
        $currentTrackIndex = $queue.length - 1;
        isTransitioningOut = false;
    }
</script>

<div class="desktop-expanded-view"
     in:fly={{ y: 40, duration: 400, easing: cubicOut }}
     out:fly={{ y: 40, duration: 300, easing: cubicOut }}>

    <div class="desktop-expanded-now-playing">
        <div class="desktop-large-art">
            {#if $currentTrack}
                <ArtworkImage track={$currentTrack}>
                    {($currentTrack.title[0] ?? '?').toUpperCase()}
                </ArtworkImage>
            {/if}
        </div>
        {#if $currentTrack}
            <div class="desktop-large-title">{$currentTrack.title}</div>
            <div class="desktop-large-artist">{$currentTrack.artist}</div>
        {/if}
    </div>

    <div class="expanded-similar-pane">
        <div class="pane-content-wrapper similar-wrapper">
            <div class="expanded-similar-header">Similar Tracks</div>

            <div class="expanded-similar-list" class:is-hiding={isTransitioningOut}>
                {#if $isFetchingSimilar && !isTransitioningOut}
                    <div class="expanded-similar-msg" in:fade={{ duration: 200, delay: 100 }}>Loading...</div>
                {:else if $similarTracks.length === 0 && !isTransitioningOut}
                    <div class="expanded-similar-msg" in:fade={{ duration: 200, delay: 100 }}>No similar tracks found.</div>
                {:else}
                    {#each $similarTracks as track, i (track.id + '_' + i)}
                        <!-- svelte-ignore a11y-click-events-have-key-events -->
                        <!-- svelte-ignore a11y-no-static-element-interactions -->
                        <div class="expanded-similar-item"
                             in:fly={{ y: 20, duration: 400, delay: i * 40, easing: cubicOut }}
                             on:click={() => handleSimilarClick(track)}>
                            <div class="similar-item-art">
                                <ArtworkImage {track}>
                                    {(track.title[0] ?? '?').toUpperCase()}
                                </ArtworkImage>
                            </div>
                            <div class="similar-item-info">
                                <div class="similar-item-title">{track.title}</div>
                                <div class="similar-item-artist">{track.artist}</div>
                            </div>
                        </div>
                    {/each}
                {/if}
            </div>
        </div>
    </div>

    <div class="expanded-queue-pane">
        <div class="pane-content-wrapper queue-wrapper">
            <QueuePanel inline={true} />
        </div>
    </div>
</div>

<style>
    .desktop-expanded-view {
        position: fixed;
        top: 0;
        bottom: 90px;
        left: 260px;
        right: 0;
        z-index: 400;
        display: flex;
        flex-direction: row;
        background: var(--bg-surface);
        border-bottom: 1px solid var(--border-subtle);
    }

    .desktop-expanded-now-playing {
        flex: 1;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        padding: 40px;
        gap: 24px;
        min-width: 320px;
        position: relative;
    }

    .desktop-large-art {
        width: min(500px, 100%);
        aspect-ratio: 1;
        border-radius: 12px;
        box-shadow: 0 12px 48px rgba(0, 0, 0, 0.4);
        font-size: 64px;
        flex-shrink: 0;
        transition: width 0.5s cubic-bezier(0.16, 1, 0.3, 1);
    }

    .desktop-large-title {
        font-size: 36px;
        font-weight: 800;
        text-align: center;
        width: 100%;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        padding: 0 20px;
    }

    .desktop-large-artist {
        font-size: 20px;
        color: var(--text-secondary);
        text-align: center;
        width: 100%;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        padding: 0 20px;
    }

    .pane-content-wrapper {
        display: flex;
        flex-direction: column;
        height: 100%;
        flex-shrink: 0;
    }

    .similar-wrapper {
        width: 440px;
    }

    .queue-wrapper {
        width: 400px;
    }

    .expanded-similar-pane, .expanded-queue-pane {
        min-width: 0;
        flex-shrink: 0;
        background: var(--bg-surface);
        border-left: 1px solid var(--border-subtle);
        overflow: hidden;
        opacity: 1;
        transition: max-width 0.6s cubic-bezier(0.16, 1, 0.3, 1), opacity 0.4s ease, border-color 0.4s ease;
    }

    .expanded-similar-pane {
        width: 440px;
        max-width: 440px;
    }

    .expanded-queue-pane {
        width: 400px;
        max-width: 400px;
    }

    .expanded-similar-header {
        padding: 0 16px;
        display: flex;
        align-items: center;
        height: 60px;
        font-size: 16px;
        font-weight: 700;
        border-bottom: 1px solid var(--border-subtle);
        flex-shrink: 0;
    }

    .expanded-similar-list {
        flex: 1;
        overflow-y: auto;
        padding: 24px;
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
        gap: 20px;
        align-content: flex-start;
        transition: opacity 0.3s cubic-bezier(0.4, 0, 0.2, 1), transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
        opacity: 1;
        transform: translateY(0) scale(1);
    }

    .expanded-similar-list.is-hiding {
        opacity: 0;
        transform: translateY(10px) scale(0.97);
        pointer-events: none;
    }

    .expanded-similar-item {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 12px;
        padding: 16px;
        border-radius: 12px;
        cursor: pointer;
        transition: transform 0.4s cubic-bezier(0.175, 0.885, 0.32, 1.275), background 0.3s ease, box-shadow 0.3s ease;
        background: var(--bg-elevated);
        border: 1px solid var(--border-subtle);
        will-change: transform;
    }

    .expanded-similar-item:hover {
        background: var(--bg-hover);
        transform: scale(1.05);
        box-shadow: 0 12px 24px rgba(0, 0, 0, 0.15);
    }

    .similar-item-art {
        width: 100%;
        aspect-ratio: 1;
        border-radius: 8px;
        flex-shrink: 0;
        font-size: 32px;
        box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
    }

    .similar-item-info {
        display: flex;
        flex-direction: column;
        gap: 4px;
        width: 100%;
        text-align: center;
    }

    .similar-item-title {
        font-size: 15px;
        font-weight: 700;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        width: 100%;
    }

    .similar-item-artist {
        font-size: 13px;
        color: var(--text-secondary);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        width: 100%;
    }

    .expanded-similar-msg {
        padding: 24px;
        text-align: center;
        color: var(--text-secondary);
        font-size: 14px;
    }

    @media (max-width: 1550px) {
        .expanded-similar-pane {
            max-width: 0;
            opacity: 0;
            border-left-color: transparent;
            pointer-events: none;
        }
    }

    @media (max-width: 1000px) {
        .expanded-queue-pane {
            max-width: 0;
            opacity: 0;
            border-left-color: transparent;
            pointer-events: none;
        }
    }
</style>
