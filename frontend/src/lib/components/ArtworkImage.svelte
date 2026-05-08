<script lang="ts">
    import {onMount} from 'svelte';
    import {
        enrichedArtworkByTrackId,
        enrichArtwork,
        placeholderGradient,
        proxyArtwork,
        trackArtworkUrl
    } from '$lib/utils/artwork';
    import type {Track} from '$lib/utils/types';

    export let src: string | null | undefined = undefined;
    export let track: Track | null | undefined = undefined;
    export let seed: string = '';
    export let autoEnrich = true;

    let mounted = false;

    $: resolvedSrc = track ? trackArtworkUrl(track, $enrichedArtworkByTrackId) : (src || null);
    $: resolvedSeed = seed || (track ? track.artist + track.title : '');
    $: proxiedSrc = proxyArtwork(resolvedSrc);
    $: if (mounted && autoEnrich && track) {
        enrichArtwork(track).catch(() => {});
    }

    let imgSrc: string | undefined = undefined;
    let imageVisible = false;
    let failed = false;
    let retried = false;
    let retryTimer: ReturnType<typeof setTimeout> | null = null;
    let imgKey = 0;

    function clearTimer() {
        if (retryTimer !== null) { clearTimeout(retryTimer); retryTimer = null; }
    }

    $: {
        if (proxiedSrc !== imgSrc) {
            clearTimer();
            imageVisible = false;
            retried = false;
            failed = false;
            imgSrc = proxiedSrc;
        }
    }

    function onLoad() {
        clearTimer();
        imageVisible = true;
    }

    function onError() {
        if (!retried && imgSrc) {
            retried = true;
            retryTimer = setTimeout(() => { imgKey++; }, 3000);
        } else {
            clearTimer();
            failed = true;
            imageVisible = false;
        }
    }

    onMount(() => {
        mounted = true;
        if (autoEnrich && track) enrichArtwork(track).catch(() => {});
        return clearTimer;
    });
</script>

<div class="art-root">
    <div class="art-placeholder"
         class:loading={!!imgSrc && !imageVisible && !failed}
         style="background: {placeholderGradient(resolvedSeed)}">
        {#if !imageVisible}<slot/>{/if}
    </div>
    {#if imgSrc && !failed}
        {#key imgKey}
            <img src={imgSrc} alt=""
                 class:visible={imageVisible}
                 on:load={onLoad}
                 on:error={onError}>
        {/key}
    {/if}
</div>

<style>
    .art-root {
        position: relative;
        width: 100%;
        height: 100%;
        border-radius: inherit;
        overflow: hidden;
    }

    .art-placeholder, img {
        position: absolute;
        inset: 0;
        width: 100%;
        height: 100%;
        border-radius: inherit;
    }

    .art-placeholder {
        display: flex;
        align-items: center;
        justify-content: center;
        overflow: hidden;
    }

    .art-placeholder.loading::after {
        content: '';
        position: absolute;
        inset: 0;
        background: linear-gradient(
            105deg,
            transparent 30%,
            rgba(255, 255, 255, 0.08) 50%,
            transparent 70%
        );
        background-size: 200% 100%;
        animation: shimmer 1.6s ease-in-out infinite;
    }

    @keyframes shimmer {
        0%   { background-position: 200% 0; }
        100% { background-position: -200% 0; }
    }

    img {
        object-fit: cover;
        display: block;
        opacity: 0;
        transition: opacity 0.25s ease;
    }

    img.visible {
        opacity: 1;
    }
</style>
