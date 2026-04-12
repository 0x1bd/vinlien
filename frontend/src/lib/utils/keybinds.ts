export interface Keybind {
    description: string;
    keys: string[];
    code: string;
    shift?: boolean;
}

export const KEYBINDS: Keybind[] = [
    {description: 'Play / Pause', keys: ['Space'], code: 'Space'},
    {description: 'Next Track', keys: ['→'], code: 'ArrowRight'},
    {description: 'Previous Track', keys: ['←'], code: 'ArrowLeft'},
    {description: 'Seek Forward 10s', keys: ['Shift', '→'], code: 'ArrowRight', shift: true},
    {description: 'Seek Backward 10s', keys: ['Shift', '←'], code: 'ArrowLeft', shift: true},
    {description: 'Mute / Unmute', keys: ['M'], code: 'KeyM'},
    {description: 'Cycle Repeat', keys: ['R'], code: 'KeyR'},
    {description: 'Toggle Queue', keys: ['Q'], code: 'KeyQ'},
];
