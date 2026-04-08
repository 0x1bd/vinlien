import {user} from '$lib/utils/store';

let isRefreshing = false;
let refreshPromise: Promise<boolean> | null = null;

type ApiRequestOptions = Omit<RequestInit, 'body'> & {
    body?: BodyInit | Record<string, unknown> | unknown[] | null;
};

function isNativeBody(body: unknown): body is BodyInit {
    return typeof body === 'string'
        || body instanceof Blob
        || body instanceof FormData
        || body instanceof URLSearchParams
        || body instanceof ReadableStream
        || body instanceof ArrayBuffer
        || ArrayBuffer.isView(body);
}

export async function apiRequest(endpoint: string, options: ApiRequestOptions = {}) {
    const headers: Record<string, string> = {...options.headers as Record<string, string>};
    const inputBody = options.body;
    let body: BodyInit | null | undefined;

    if (inputBody == null || isNativeBody(inputBody)) {
        body = inputBody;
    } else {
        body = JSON.stringify(inputBody);
        headers['Content-Type'] = 'application/json';
    }

    const requestOptions: RequestInit = {
        ...options,
        credentials: 'include',
        headers,
        body
    };

    let res = await fetch(endpoint, requestOptions);

    if (res.status === 401 && endpoint !== '/api/auth/login' && endpoint !== '/api/auth/refresh') {
        if (!isRefreshing) {
            isRefreshing = true;
            refreshPromise = fetch('/api/auth/refresh', {method: 'POST', credentials: 'include'})
                .then(r => r.ok)
                .catch(() => false)
                .finally(() => {
                    isRefreshing = false;
                });
        }

        const refreshOk = await refreshPromise;

        if (refreshOk) {
            res = await fetch(endpoint, requestOptions);
        } else {
            user.set(null);
            throw new Error("Session expired.");
        }
    }

    if (!res.ok) throw new Error(await res.text());

    const text = await res.text();
    try {
        return text ? JSON.parse(text) : null;
    } catch {
        return text;
    }
}