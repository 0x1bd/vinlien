import {writable} from 'svelte/store';

export const toasts = writable<{ id: number, message: string, type: string }[]>([]);

const shownErrors = new Set<string>();

export function addToast(message: string, type: 'success' | 'error' | 'info' = 'info') {
    if (type === 'error') {
        if (shownErrors.has(message)) return;
        shownErrors.add(message);
        setTimeout(() => shownErrors.delete(message), 10000);
    }

    const id = Date.now();
    toasts.update(all => [...all, {id, message, type}]);
    const duration = type === 'error' ? 8000 : 3000;
    setTimeout(() => {
        toasts.update(all => all.filter(t => t.id !== id));
    }, duration);
}