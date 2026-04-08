<script>
    import {user} from '$lib/utils/store';
    import {goto} from '$app/navigation';

    let username = '';
    let pass = '';
    let isRegistering = false;
    let message = '';

    async function submit() {
        message = 'Loading...';
        const url = isRegistering ? '/api/auth/register' : '/api/auth/login';
        const res = await fetch(url, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({username, pass})
        });

        if (res.ok) {
            if (isRegistering) {
                message = 'Registered! Waiting for admin approval.';
                isRegistering = false;
            } else {
                $user = await res.json();
                goto('/');
            }
        } else {
            message = isRegistering ? 'Username taken' : await res.text();
        }
    }
</script>

<div class="login-container">
    <div class="box">
        <div class="brand">
            <h1>Vinlien</h1>
        </div>
        <p>{isRegistering ? 'Request an account' : 'Sign in to continue'}</p>

        <div class="inputs">
            <input type="text" bind:value={username} placeholder="Username"/>
            <input type="password" bind:value={pass} placeholder="Password"
                   on:keydown={e => e.key === 'Enter' && submit()}/>
        </div>

        <button class="submit" on:click={submit}>{isRegistering ? 'Register' : 'Log In'}</button>
        <!-- svelte-ignore a11y-click-events-have-key- -->
        <!-- svelte-ignore a11y-no-static-element-interactions -->
        <!-- svelte-ignore a11y-click-events-have-key-events -->
        <div class="toggle" on:click={() => isRegistering = !isRegistering}>
            {isRegistering ? 'Already have an account? Log In' : 'Need an invite? Register'}
        </div>
        {#if message}
            <div class="msg">{message}</div>
        {/if}
    </div>
</div>

<style>
    .login-container {
        display: flex;
        justify-content: center;
        align-items: center;
        height: 100vh;
        background: var(--bg-base);
    }

    .box {
        background: var(--bg-surface);
        border: 1px solid var(--border-subtle);
        padding: 48px;
        display: flex;
        flex-direction: column;
        gap: 24px;
        width: 420px;
        text-align: center;
        border-radius: 12px;
    }

    .inputs {
        display: flex;
        flex-direction: column;
        gap: 16px;
    }

    .submit {
        background: var(--text-primary);
        color: var(--bg-base);
        padding: 16px;
        font-size: 16px;
    }

    .toggle {
        font-size: 14px;
        color: var(--text-secondary);
        cursor: pointer;
    }

    .toggle:hover {
        color: var(--text-primary);
    }

    .msg {
        color: var(--danger-color);
        font-size: 14px;
        background: rgba(239, 68, 68, 0.1);
        padding: 12px;
        border-radius: 6px;
    }
</style>