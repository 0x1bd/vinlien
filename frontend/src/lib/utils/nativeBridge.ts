import type {VinlienNativeBridge} from '$lib/utils/types';

export function nativeBridge(): VinlienNativeBridge | undefined {
    if (typeof window === 'undefined') return undefined;
    return window.vinlienNative ?? window.vinlienElectron;
}