<script lang="ts">
    import {showVolumeSlider, silenceSkip, silenceSkipThreshold, theme, useRecommendations, continuePlaylist} from '$lib/utils/store';
    import {themes} from '$lib/utils/themes';
</script>

<div class="header">
    <h2 class="page-title">Settings</h2>
</div>

<div class="settings-container">
    <div class="setting-item theme-setting">
        <div class="info">
            <h3>Theme</h3>
            <p>Choose the appearance of the app.</p>
        </div>
        <div class="theme-options">
            {#each Object.values(themes) as t}
                <button
                    class="theme-card"
                    class:selected={$theme === t.id}
                    on:click={() => ($theme = t.id)}
                >
                    <div class="theme-swatch">
                        <div class="swatch-sidebar" style="background:{t.vars['--bg-sidebar']}"></div>
                        <div class="swatch-main" style="background:{t.vars['--bg-base']}">
                            <div class="swatch-surface" style="background:{t.vars['--bg-surface']}"></div>
                            <div class="swatch-accent" style="background:{t.vars['--accent-color']}"></div>
                        </div>
                    </div>
                    <span class="theme-label" style="color:{t.vars['--text-primary']};background:{t.vars['--bg-elevated']}">{t.name}</span>
                </button>
            {/each}
        </div>
    </div>

    <div class="setting-item">
        <div class="info">
            <h3>Autoplay</h3>
            <p>When the queue ends, automatically play recommended tracks. Disable to stop after the last track.</p>
        </div>
        <label class="switch">
            <input type="checkbox" bind:checked={$useRecommendations}>
            <span class="slider"></span>
        </label>
    </div>

    <div class="setting-item">
        <div class="info">
            <h3>Continue Playlist</h3>
            <p>When clicking a song in a playlist, queue the rest of the playlist. Disable to play only that track and use autoplay recommendations instead.</p>
        </div>
        <label class="switch">
            <input type="checkbox" bind:checked={$continuePlaylist}>
            <span class="slider"></span>
        </label>
    </div>

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
                    <button class="threshold-btn"
                            on:click={() => $silenceSkipThreshold = Math.max(1, $silenceSkipThreshold - 1)}>−
                    </button>
                    <span class="threshold-val">{$silenceSkipThreshold}</span>
                    <button class="threshold-btn"
                            on:click={() => $silenceSkipThreshold = Math.min(10, $silenceSkipThreshold + 1)}>+
                    </button>
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

</div>

<style>
    .settings-container {
        display: flex;
        flex-direction: column;
        gap: 16px;
        max-width: 700px;
    }

    .setting-item.theme-setting {
        flex-direction: column;
        align-items: flex-start;
        gap: 16px;
    }

    .theme-options {
        display: flex;
        gap: 12px;
        flex-wrap: wrap;
    }

    .theme-card {
        background: none;
        border: 2px solid transparent;
        padding: 0;
        border-radius: 10px;
        overflow: hidden;
        cursor: pointer;
        transition: border-color 0.2s, transform 0.15s;
        width: 90px;
    }

    .theme-card:hover {
        transform: scale(1.04);
        filter: none;
    }

    .theme-card.selected {
        border-color: var(--accent-color);
    }

    .theme-swatch {
        display: flex;
        height: 56px;
        width: 100%;
    }

    .swatch-sidebar {
        width: 28%;
        flex-shrink: 0;
    }

    .swatch-main {
        flex: 1;
        display: flex;
        flex-direction: column;
        gap: 4px;
        padding: 6px 6px 0;
    }

    .swatch-surface {
        height: 10px;
        border-radius: 3px;
    }

    .swatch-accent {
        height: 6px;
        border-radius: 3px;
        width: 60%;
    }

    .theme-label {
        display: block;
        width: 100%;
        text-align: center;
        font-size: 12px;
        font-weight: 600;
        padding: 6px 0;
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
        .setting-item:not(.theme-setting) {
            flex-direction: column;
            align-items: flex-start;
        }
    }
</style>