export type ThemeId = 'dark' | 'amoled' | 'nord' | 'light' | 'pastel';

export interface Theme {
    id: ThemeId;
    name: string;
    vars: Record<string, string>;
}

export const themes: Record<ThemeId, Theme> = {
    dark: {
        id: 'dark',
        name: 'Dark',
        vars: {
            '--bg-base': '#121212',
            '--bg-surface': '#181818',
            '--bg-elevated': '#282828',
            '--bg-hover': '#2a2a2a',
            '--bg-sidebar': '#000000',
            '--text-primary': '#ffffff',
            '--text-secondary': '#a3a3a3',
            '--accent-color': '#2563eb',
            '--danger-color': '#ef4444',
            '--border-subtle': '#282828',
            '--range-track': '#404040',
            '--scrollbar-thumb': 'rgba(255,255,255,0.10)',
            '--scrollbar-thumb-hover': 'rgba(255,255,255,0.20)',
        },
    },
    amoled: {
        id: 'amoled',
        name: 'AMOLED',
        vars: {
            '--bg-base': '#000000',
            '--bg-surface': '#050505',
            '--bg-elevated': '#101010',
            '--bg-hover': '#141414',
            '--bg-sidebar': '#000000',
            '--text-primary': '#ffffff',
            '--text-secondary': '#808080',
            '--accent-color': '#2563eb',
            '--danger-color': '#ef4444',
            '--border-subtle': '#1a1a1a',
            '--range-track': '#303030',
            '--scrollbar-thumb': 'rgba(255,255,255,0.08)',
            '--scrollbar-thumb-hover': 'rgba(255,255,255,0.15)',
        },
    },
    nord: {
        id: 'nord',
        name: 'Nord',
        vars: {
            '--bg-base': '#2e3440',
            '--bg-surface': '#3b4252',
            '--bg-elevated': '#434c5e',
            '--bg-hover': '#4c566a',
            '--bg-sidebar': '#242933',
            '--text-primary': '#eceff4',
            '--text-secondary': '#9099a8',
            '--accent-color': '#5e81ac',
            '--danger-color': '#bf616a',
            '--border-subtle': '#434c5e',
            '--range-track': '#4c566a',
            '--scrollbar-thumb': 'rgba(236,239,244,0.10)',
            '--scrollbar-thumb-hover': 'rgba(236,239,244,0.20)',
        },
    },
    pastel: {
        id: 'pastel',
        name: 'Pastel',
        vars: {
            '--bg-base': '#fdf2f8',
            '--bg-surface': '#fff8fc',
            '--bg-elevated': '#f5e6f3',
            '--bg-hover': '#ecd4ea',
            '--bg-sidebar': '#f0dff0',
            '--text-primary': '#2d1b2e',
            '--text-secondary': '#8b6b8e',
            '--accent-color': '#a855f7',
            '--danger-color': '#e879a5',
            '--border-subtle': '#e8c8e8',
            '--range-track': '#d4aad4',
            '--scrollbar-thumb': 'rgba(168,85,247,0.20)',
            '--scrollbar-thumb-hover': 'rgba(168,85,247,0.35)',
        },
    },
    light: {
        id: 'light',
        name: 'Light',
        vars: {
            '--bg-base': '#f5f5f5',
            '--bg-surface': '#ffffff',
            '--bg-elevated': '#ebebeb',
            '--bg-hover': '#e0e0e0',
            '--bg-sidebar': '#efefef',
            '--text-primary': '#111111',
            '--text-secondary': '#555555',
            '--accent-color': '#2563eb',
            '--danger-color': '#dc2626',
            '--border-subtle': '#d1d1d1',
            '--range-track': '#c0c0c0',
            '--scrollbar-thumb': 'rgba(0,0,0,0.15)',
            '--scrollbar-thumb-hover': 'rgba(0,0,0,0.25)',
        },
    },
};

export function applyTheme(id: ThemeId) {
    const t = themes[id];
    if (!t) return;
    const root = document.documentElement;
    for (const [prop, val] of Object.entries(t.vars)) {
        root.style.setProperty(prop, val);
    }
}
