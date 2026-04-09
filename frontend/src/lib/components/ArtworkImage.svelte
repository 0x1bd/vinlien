<script lang="ts">
    import {placeholderGradient} from '$lib/utils/artwork';

    export let src: string | null | undefined = undefined;
    export let seed: string = '';

    let error = false;
    let loading = false;
    let loadedSrc: string | null = null;

    $: {
        const next = src ?? null;
        if (next !== loadedSrc) {
            error = false;
            loading = !!next;
            if (!next) loadedSrc = null;
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
             on:load={() => { loading = false; loadedSrc = src ?? null; }}
             on:error={() => { loading = false; error = true; loadedSrc = null; }}>
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
