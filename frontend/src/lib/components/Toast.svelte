<script lang="ts">
    import {toasts} from '$lib/utils/toast';
    import {fly, fade} from 'svelte/transition';
    import {flip} from 'svelte/animate';
</script>

<div class="toast-container" aria-live="polite" aria-atomic="false">
    {#each $toasts as t (t.id)}
        <div
            class="toast {t.type}"
            role="status"
            in:fly={{x: 20, duration: 220, opacity: 0}}
            out:fade={{duration: 160}}
            animate:flip={{duration: 200}}
        >
            <span class="bar" aria-hidden="true"></span>
            <span class="msg">{t.message}</span>
        </div>
    {/each}
</div>

<style>
    .toast-container {
        position: fixed;
        bottom: 110px;
        right: 20px;
        z-index: 9999;
        display: flex;
        flex-direction: column;
        gap: 8px;
        pointer-events: none;
    }

    .toast {
        display: flex;
        align-items: stretch;
        background: var(--bg-elevated);
        border: 1px solid var(--border-subtle);
        border-radius: 6px;
        overflow: hidden;
        min-width: 220px;
        max-width: 300px;
        box-shadow: 0 4px 20px rgba(0, 0, 0, 0.25);
        pointer-events: all;
    }

    .bar {
        width: 3px;
        flex-shrink: 0;
    }

    .toast.success .bar { background: var(--success-color); }
    .toast.error   .bar { background: var(--danger-color); }
    .toast.info    .bar { background: var(--accent-color); }

    .msg {
        padding: 11px 14px;
        font-size: 13px;
        font-weight: 500;
        color: var(--text-primary);
        line-height: 1.4;
    }

    @media (max-width: 600px) {
        .toast-container {
            bottom: auto;
            top: 16px;
            right: 12px;
            left: 12px;
        }

        .toast {
            max-width: 100%;
        }
    }
</style>
