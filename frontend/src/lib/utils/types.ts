export interface Track {
    id: string;
    title: string;
    artist: string;
    artists: string[];
    durationMs: number;
    streamUrl?: string | null;
    artworkUrl?: string | null;
    canonicalId?: string | null;
    lastFmUrl?: string | null;
}

export interface Album {
    id: string;
    title: string;
    artist: string;
    artworkUrl?: string | null;
    year?: number | null;
    tracks: Track[];
}

export interface SearchResponse {
    tracks: Track[];
    albums: Album[];
}

export interface Playlist {
    id: string;
    userId: string;
    name: string;
    description?: string | null;
    imageUrl?: string | null;
    tracks: Track[];
}

export interface User {
    id: string;
    username: string;
    role: string;
    token?: string;
    requiresPasswordChange?: boolean;
}

export interface HomeFeed {
    recentlyPlayed: Track[];
    listenAgain: Track[];
    forgottenFavorites: Track[];
    artists: string[];
}

export interface UserStat {
    username: string;
    playCount: number;
}

export interface AdminStats {
    totalUsers: number;
    totalPlays: number;
    uniqueTracks: number;
    totalPlaytimeMs: number;
    topUsers: UserStat[];
}

export interface AdminStatsResponse {
    stats: AdminStats;
    pending: User[];
}

export interface ArtistInfo {
    name: string;
    bio: string;
    tags: string[];
    imageUrl?: string | null;
}