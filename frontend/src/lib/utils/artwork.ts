export function placeholderGradient(seed: string): string {
    let h = 0;
    for (let i = 0; i < seed.length; i++) h = (Math.imul(31, h) + seed.charCodeAt(i)) | 0;
    const hue1 = (h >>> 0) % 360;
    const hue2 = (hue1 + 40 + ((h >>> 8) % 80)) % 360;
    const angle = (h >>> 16) % 360;
    return `linear-gradient(${angle}deg, hsl(${hue1},60%,30%), hsl(${hue2},70%,50%))`;
}
