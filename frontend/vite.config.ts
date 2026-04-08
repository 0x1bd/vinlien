import {sveltekit} from '@sveltejs/kit/vite';
import {defineConfig} from 'vite';

export default defineConfig({
    plugins: [sveltekit()],
    server: {
        proxy: {
            '/api': {
                target: 'http://localhost:8080',
                changeOrigin: true
            }
        }
    },
    build: {
        rollupOptions: {
            onwarn(warning, warn) {
                if (warning.code === 'MISSING_EXPORT' && warning.message.includes('svelte/src/runtime')) return;
                warn(warning);
            }
        }
    }
});