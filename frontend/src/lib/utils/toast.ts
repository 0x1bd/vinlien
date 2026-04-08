import {writable} from 'svelte/store';

export const toasts = writable<{ id: number, message: string, type: string }[]>([]);

export function addToast(message: string, type: 'success' | 'error' | 'info' = 'info') {
    const id = Date.now();
    toasts.update(all => [...all, {id, message, type}]);
    setTimeout(() => {
        toasts.update(all => all.filter(t => t.id !== id));
    }, 3000);
}