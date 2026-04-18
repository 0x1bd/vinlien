<script lang="ts">
    import {placeholderGradient, proxyArtwork} from '$lib/utils/artwork';

    export let src: string | null | undefined = undefined;
    export let seed: string = '';

    $: proxiedSrc = proxyArtwork(src);

    let imgSrc: string | undefined = undefined;
    let imageVisible = false;
    let failed = false;      // true = gave up after retries; reset only when src changes
    let retried = false;
    let retryTimer: ReturnType<typeof setTimeout> | null = null;
    let imgKey = 0;

    function clearTimer() {
        if (retryTimer !== null) { clearTimeout(retryTimer); retryTimer = null; }
    }

    // Reset state whenever the source URL changes
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
</script>

<div class="art-root">
    <div class="art-placeholder"
         class:loading={!!imgSrc && !imageVisible && !failed}
         style="background: {placeholderGradient(seed)}">
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
