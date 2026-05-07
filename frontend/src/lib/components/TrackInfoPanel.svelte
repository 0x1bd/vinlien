<script lang="ts">
    import { resolvedStreamUrl, resolvedStreamProvider } from '$lib/utils/store';

    export let track: any;
    export let onClose: () => void;

    function providerLabel(id: string): string {
        if (id.startsWith('lastfm:'))   return 'Last.fm';
        if (id.startsWith('itunes:'))   return 'iTunes';
        if (id.startsWith('sc:'))       return 'SoundCloud';
        if (id.startsWith('ytmusic:')) return 'YouTube Music';
        return id.split(':')[0];
    }
</script>

<!-- svelte-ignore a11y-click-events-have-key-events -->
<!-- svelte-ignore a11y-no-static-element-interactions -->
<div class="panel" on:click|stopPropagation>
    <div class="header">
        <span class="title">Track Info</span>
        <span class="badge">{providerLabel(track.id)}</span>
        <button class="close-btn" on:click={onClose} aria-label="Close">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"></line>
                <line x1="6" y1="6" x2="18" y2="18"></line>
            </svg>
        </button>
    </div>
    <div class="rows">
        <div class="row"><span class="label">Title</span>   <span class="val">{track.title}</span></div>
        <div class="row"><span class="label">Artist</span>  <span class="val">{track.artist}</span></div>
        <div class="row"><span class="label">Track ID</span><span class="val mono">{track.id}</span></div>
        {#if track.canonicalId}
            <div class="row"><span class="label">Canonical ID</span><span class="val mono">{track.canonicalId}</span></div>
        {/if}
        {#if track.durationMs}
            <div class="row">
                <span class="label">Duration</span>
                <span class="val">{Math.floor(track.durationMs/60000)}:{String(Math.floor((track.durationMs%60000)/1000)).padStart(2,'0')}</span>
            </div>
        {/if}
        {#if track.lastFmUrl}
            <div class="row">
                <span class="label">Last.fm</span>
                <a class="val link" href={track.lastFmUrl} target="_blank" rel="noopener noreferrer" on:click|stopPropagation>{track.lastFmUrl}</a>
            </div>
        {/if}
        {#if track.streamUrl}
            <div class="row">
                <span class="label">Stream</span>
                <a class="val link" href={track.streamUrl} target="_blank" rel="noopener noreferrer" on:click|stopPropagation>{providerLabel(track.id)}</a>
            </div>
        {/if}
        {#if $resolvedStreamUrl}
            <div class="row stream-url-row">
                <span class="label">Stream</span>
                <div class="stream-url-content">
                    <span class="provider-badge">{$resolvedStreamProvider}</span>
                    <a class="stream-link" href={$resolvedStreamUrl} target="_blank" rel="noopener noreferrer" on:click|stopPropagation>{$resolvedStreamUrl}</a>
                </div>
            </div>
        {/if}
        {#if track.artworkUrl}
            <div class="row">
                <span class="label">Artwork</span>
                <a class="val link mono" href={track.artworkUrl} target="_blank" rel="noopener noreferrer" on:click|stopPropagation>{track.artworkUrl}</a>
            </div>
        {/if}
    </div>
</div>

<style>
    .panel {
        position: fixed;
        bottom: 90px;
        right: 0;
        width: 380px;
        background: var(--bg-elevated);
        border: 1px solid var(--border-subtle);
        border-radius: 8px 8px 0 0;
        padding: 16px;
        box-shadow: 0 -8px 24px rgba(0,0,0,.4);
        z-index: 600;
    }

    .header {
        display: flex;
        align-items: center;
        gap: 10px;
        margin-bottom: 12px;
    }

    .title { font-size: 13px; font-weight: 700; color: var(--text-primary); flex: 1; }

    .badge {
        font-size: 11px; font-weight: 600;
        background: var(--accent-color); color: var(--bg-base);
        padding: 2px 8px; border-radius: 10px;
    }

    .close-btn {
        background: transparent; border: none; padding: 4px;
        color: var(--text-secondary); display: flex;
        align-items: center; justify-content: center; cursor: pointer;
    }
    .close-btn:hover { color: var(--text-primary); }

    .rows { display: flex; flex-direction: column; gap: 8px; }

    .row { display: flex; gap: 12px; align-items: baseline; }

    .label {
        font-size: 11px; font-weight: 600; text-transform: uppercase;
        color: var(--text-secondary); min-width: 80px; flex-shrink: 0;
    }

    .val {
        font-size: 12px; color: var(--text-primary);
        overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0;
    }

    .val.mono { font-family: monospace; font-size: 11px; }
    .val.link { color: var(--accent-color); text-decoration: none; }
    .val.link:hover { text-decoration: underline; }

    .stream-url-row {
        align-items: flex-start;
    }

    .stream-url-content {
        display: flex; flex-direction: column; gap: 4px; min-width: 0; flex: 1;
    }

    .provider-badge {
        display: inline-block; font-size: 10px; font-weight: 600;
        background: var(--accent-color); color: var(--bg-base);
        padding: 1px 8px; border-radius: 8px; align-self: flex-start;
    }

    .stream-link {
        font-family: monospace; font-size: 10px;
        color: var(--accent-color); text-decoration: none;
        overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
        word-break: break-all;
    }
    .stream-link:hover { text-decoration: underline; }

    @media (max-width: 600px) {
        .panel { bottom: 0; left: 0; right: 0; width: 100%; border-radius: 0; }
    }
</style>
