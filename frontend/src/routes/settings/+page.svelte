<script lang="ts">
    import {showVolumeSlider, theme, useRecommendations, continuePlaylist} from '$lib/utils/store';
    import {KEYBINDS} from '$lib/utils/keybinds';
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

    <div class="setting-item kb-setting">
        <div class="info">
            <h3>Keyboard Shortcuts</h3>
            <p>These shortcuts work anywhere in the app, except when typing in a text field.</p>
        </div>
        <div class="kb-grid">
            {#each KEYBINDS as kb}
                <span class="kb-desc">{kb.description}</span>
                <span class="kb-keys">
                    {#each kb.keys as key, i}
                        <kbd class="key-cap">{key}</kbd>
                        {#if i < kb.keys.length - 1}<span class="key-plus">+</span>{/if}
                    {/each}
                </span>
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

    .setting-item.kb-setting {
        flex-direction: column;
        align-items: flex-start;
        gap: 20px;
    }

    .kb-grid {
        display: grid;
        grid-template-columns: 1fr auto;
        gap: 10px 24px;
        align-items: center;
        width: 100%;
    }

    .kb-desc {
        font-size: 14px;
        color: var(--text-primary);
    }

    .kb-keys {
        display: flex;
        align-items: center;
        gap: 4px;
        justify-content: flex-end;
    }

    .key-cap {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        background: var(--bg-base);
        border: 1px solid var(--border-subtle);
        border-bottom-width: 3px;
        border-radius: 5px;
        padding: 3px 8px;
        font-size: 12px;
        font-family: inherit;
        font-weight: 600;
        color: var(--text-primary);
        min-width: 28px;
        line-height: 1.4;
        box-shadow: 0 1px 0 rgba(0,0,0,0.3);
        white-space: nowrap;
    }

    .key-plus {
        font-size: 11px;
        color: var(--text-secondary);
        user-select: none;
    }

    @media (max-width: 768px) {
        .setting-item:not(.theme-setting):not(.kb-setting) {
            flex-direction: column;
            align-items: flex-start;
        }
    }
</style>