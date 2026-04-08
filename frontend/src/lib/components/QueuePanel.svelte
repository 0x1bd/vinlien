<script lang="ts">
    import {queue, currentTrackIndex, showQueuePanel} from '$lib/utils/store';
    import TrackRow from './TrackRow.svelte';

    let touchStartX = 0;

    function handleTouchStart(e: TouchEvent) {
        if (window.innerWidth <= 600) {
            touchStartX = e.touches[0].clientX;
        }
    }

    function handleTouchEnd(e: TouchEvent) {
        if (window.innerWidth <= 600) {
            const touchEndX = e.changedTouches[0].clientX;
            const diffX = touchEndX - touchStartX;
            if (diffX > 80) {
                $showQueuePanel = false;
            }
        }
    }
</script>

<div class="queue-panel" class:open={$showQueuePanel}
     on:touchstart={handleTouchStart}
     on:touchend={handleTouchEnd}>
    <div class="header">
        <h3>Queue</h3>
        <button class="icon-btn" on:click={() => $showQueuePanel = false}>X</button>
    </div>
    <div class="queue-list">
        {#each $queue as track, i}
            <div class="queue-item" class:playing={i === $currentTrackIndex}>
                <TrackRow {track} onPlay={() => $currentTrackIndex = i}/>
            </div>
        {/each}
    </div>
</div>

<style>
    .queue-panel {
        position: fixed;
        right: 0;
        top: 0;
        bottom: 90px;
        width: 320px;
        background: var(--bg-surface);
        border-left: 1px solid var(--border-subtle);
        transform: translateX(100%);
        transition: 0.3s;
        z-index: 400;
        display: flex;
        flex-direction: column;
    }

    .queue-panel.open {
        transform: translateX(0);
    }

    .header {
        padding: 16px;
        display: flex;
        justify-content: space-between;
        align-items: center;
        border-bottom: 1px solid var(--border-subtle);
    }

    .header h3 {
        font-size: 16px;
        font-weight: 700;
    }

    .queue-list {
        flex: 1;
        overflow-y: auto;
        padding: 8px;
    }

    .queue-item.playing {
        background: rgba(37, 99, 235, 0.1);
        border-radius: 6px;
    }

    @media (max-width: 600px) {
        .queue-panel {
            width: 100%;
            bottom: 0;
            top: 0;
            height: 100dvh;
        }

        .header h3 {
            font-size: 18px;
        }
    }
</style>