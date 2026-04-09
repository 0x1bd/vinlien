<script lang="ts">
    import {showVolumeSlider, metaProvidersOrder, audioProvidersOrder, silenceSkip, silenceSkipThreshold} from '$lib/utils/store';
    import {apiRequest} from '$lib/utils/api';
    import {onMount} from 'svelte';
    import type {Writable} from 'svelte/store';

    onMount(async () => {
        try {
            const res = await apiRequest('/api/providers');

            const newMeta = res.metadata.filter((p: string) => !$metaProvidersOrder.includes(p));
            $metaProvidersOrder = [...$metaProvidersOrder, ...newMeta].filter((p: string) => res.metadata.includes(p));

            const newAudio = res.audio.filter((p: string) => !$audioProvidersOrder.includes(p));
            $audioProvidersOrder = [...$audioProvidersOrder, ...newAudio].filter((p: string) => res.audio.includes(p));

        } catch (e) {
        }
    });

    function moveUp(store: Writable<string[]>, index: number) {
        if (index === 0) return;
        store.update(list => {
            const temp = list[index - 1];
            list[index - 1] = list[index];
            list[index] = temp;
            return list;
        });
    }

    function moveDown(store: Writable<string[]>, index: number) {
        let maxIdx = 0;
        store.subscribe(v => maxIdx = v.length - 1)();
        if (index === maxIdx) return;

        store.update(list => {
            const temp = list[index + 1];
            list[index + 1] = list[index];
            list[index] = temp;
            return list;
        });
    }
</script>

<div class="header">
    <h2 class="page-title">Settings</h2>
</div>

<div class="settings-container">
    <div class="setting-item">
        <div class="info">
            <h3>Player Volume Slider</h3>
            <p>Show the adjustable volume slider in the bottom player bar.</p>
        </div>

        <label class="switch">
            <input type="checkbox" bind:checked={$showVolumeSlider}>
            <span class="slider"></span>
        </label>
    </div>

    <div class="setting-item">
        <div class="info">
            <h3>Silence Skip</h3>
            {#if $silenceSkip}
                <p>Skipping after
                    <button class="threshold-btn" on:click={() => $silenceSkipThreshold = Math.max(1, $silenceSkipThreshold - 1)}>−</button>
                    <span class="threshold-val">{$silenceSkipThreshold}</span>
                    <button class="threshold-btn" on:click={() => $silenceSkipThreshold = Math.min(10, $silenceSkipThreshold + 1)}>+</button>
                    s of silence.
                </p>
            {:else}
                <p>Automatically skip silence at the beginning or end of tracks.</p>
            {/if}
        </div>
        <label class="switch">
            <input type="checkbox" bind:checked={$silenceSkip}>
            <span class="slider"></span>
        </label>
    </div>

    <div class="setting-item column-layout">
        <div class="info">
            <h3>Metadata Provider Priority</h3>
            <p>Arrange the services in order of preference for search results.</p>
        </div>

        <div class="priority-list">
            {#each $metaProvidersOrder as p, i}
                <div class="priority-row">
                    <span class="p-name">{i + 1}. {p}</span>
                    <div class="controls">
                        <button class="icon-btn" disabled={i === 0} on:click={() => moveUp(metaProvidersOrder, i)}>
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                                 stroke-width="2">
                                <polyline points="18 15 12 9 6 15"></polyline>
                            </svg>
                        </button>
                        <button class="icon-btn" disabled={i === $metaProvidersOrder.length - 1}
                                on:click={() => moveDown(metaProvidersOrder, i)}>
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                                 stroke-width="2">
                                <polyline points="6 9 12 15 18 9"></polyline>
                            </svg>
                        </button>
                    </div>
                </div>
            {/each}
        </div>
    </div>

    <div class="setting-item column-layout">
        <div class="info">
            <h3>Audio Stream Priority</h3>
            <p>Arrange the services in order of preference for fetching audio streams.</p>
        </div>

        <div class="priority-list">
            {#each $audioProvidersOrder as p, i}
                <div class="priority-row">
                    <span class="p-name">{i + 1}. {p}</span>
                    <div class="controls">
                        <button class="icon-btn" disabled={i === 0} on:click={() => moveUp(audioProvidersOrder, i)}>
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                                 stroke-width="2">
                                <polyline points="18 15 12 9 6 15"></polyline>
                            </svg>
                        </button>
                        <button class="icon-btn" disabled={i === $audioProvidersOrder.length - 1}
                                on:click={() => moveDown(audioProvidersOrder, i)}>
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                                 stroke-width="2">
                                <polyline points="6 9 12 15 18 9"></polyline>
                            </svg>
                        </button>
                    </div>
                </div>
            {/each}
        </div>
    </div>
</div>

<style>
    .settings-container {
        display: flex;
        flex-direction: column;
        gap: 16px;
        max-width: 700px;
    }

    .setting-item {
        background: var(--bg-surface);
        border: 1px solid var(--border-subtle);
        padding: 24px;
        border-radius: 8px;
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: 24px;
    }

    .setting-item.column-layout {
        flex-direction: column;
        align-items: flex-start;
    }

    .info h3 {
        font-size: 16px;
        color: var(--text-primary);
        font-weight: 600;
        margin-bottom: 4px;
    }

    .info p {
        font-size: 14px;
        color: var(--text-secondary);
        line-height: 1.4;
    }

    .priority-list {
        display: flex;
        flex-direction: column;
        width: 100%;
        background: var(--bg-elevated);
        border: 1px solid var(--border-subtle);
        border-radius: 8px;
        overflow: hidden;
    }

    .priority-row {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 8px 16px;
        border-bottom: 1px solid var(--border-subtle);
        background: var(--bg-elevated);
    }

    .priority-row:last-child {
        border-bottom: none;
    }

    .p-name {
        font-size: 14px;
        font-weight: 500;
    }

    .controls {
        display: flex;
        gap: 4px;
    }

    .controls button {
        padding: 6px;
        color: var(--text-secondary);
    }

    .controls button:hover:not(:disabled) {
        color: var(--text-primary);
        background: var(--bg-hover);
    }

    .controls button:disabled {
        opacity: 0.3;
        cursor: not-allowed;
    }

    .threshold-btn {
        background: var(--bg-hover);
        border: 1px solid var(--border-subtle);
        color: var(--text-primary);
        width: 22px;
        height: 22px;
        border-radius: 4px;
        font-size: 14px;
        line-height: 1;
        padding: 0;
        cursor: pointer;
        vertical-align: middle;
    }

    .threshold-btn:hover {
        background: var(--border-subtle);
    }

    .threshold-val {
        display: inline-block;
        min-width: 16px;
        text-align: center;
        font-weight: 700;
        color: var(--text-primary);
        vertical-align: middle;
    }

    .switch {
        position: relative;
        display: inline-block;
        width: 44px;
        height: 24px;
        flex-shrink: 0;
    }

    .switch input {
        opacity: 0;
        width: 0;
        height: 0;
    }

    .slider {
        position: absolute;
        cursor: pointer;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        background-color: var(--bg-elevated);
        transition: .3s;
        border-radius: 24px;
    }

    .slider:before {
        position: absolute;
        content: "";
        height: 18px;
        width: 18px;
        left: 3px;
        bottom: 3px;
        background-color: var(--text-secondary);
        transition: .3s;
        border-radius: 50%;
    }

    input:checked + .slider {
        background-color: var(--accent-color);
    }

    input:checked + .slider:before {
        transform: translateX(20px);
        background-color: #fff;
    }

    @media (max-width: 768px) {
        .setting-item:not(.column-layout) {
            flex-direction: column;
            align-items: flex-start;
        }
    }
</style>