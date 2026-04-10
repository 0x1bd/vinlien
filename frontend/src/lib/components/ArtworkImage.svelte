<script lang="ts">
    import {placeholderGradient} from '$lib/utils/artwork';

    export let src: string | null | undefined = undefined;
    export let seed: string = '';

    const LOAD_TIMEOUT_MS = 5000;

    let error = false;
    let loading = false;
    let loadedSrc: string | null = null;
    let loadTimer: ReturnType<typeof setTimeout> | null = null;

    function clearLoadTimer() {
        if (loadTimer !== null) {
            clearTimeout(loadTimer);
            loadTimer = null;
        }
    }

    function onImageSettled(success: boolean) {
        clearLoadTimer();
        loading = false;
        if (success) {
            loadedSrc = src ?? null;
        } else {
            error = true;
            loadedSrc = null;
        }
    }

    $: {
        const next = src ?? null;
        if (next !== loadedSrc) {
            error = false;
            clearLoadTimer();
            loading = !!next;
            if (!next) {
                loadedSrc = null;
            } else {
                loadTimer = setTimeout(() => onImageSettled(false), LOAD_TIMEOUT_MS);
            }
        }
    }
</script>

<div class="art-root">
    {#if src && !error}
        {#if loading}
            <div class="art-spinner"></div>
        {/if}
        <img {src} alt=""
             style:visibility={loading ? 'hidden' : 'visible'}
             on:load={() => onImageSettled(true)}
             on:error={() => onImageSettled(false)}>
    {:else}
        <div class="art-placeholder" style="background: {placeholderGradient(seed)}">
            <slot/>
        </div>
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

    img, .art-placeholder, .art-spinner {
        position: absolute;
        inset: 0;
        width: 100%;
        height: 100%;
        border-radius: inherit;
    }

    img {
        object-fit: cover;
        display: block;
    }

    .art-spinner {
        background: var(--bg-elevated);
        display: flex;
        align-items: center;
        justify-content: center;
    }

    .art-spinner::after {
        content: '';
        width: 35%;
        height: 35%;
        border: 2px solid var(--border-subtle);
        border-top-color: var(--text-secondary);
        border-radius: 50%;
        animation: spin 0.8s linear infinite;
    }

    @keyframes spin {
        to { transform: rotate(360deg); }
    }

    .art-placeholder {
        display: flex;
        align-items: center;
        justify-content: center;
    }
</style>
