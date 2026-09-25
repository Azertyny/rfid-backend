// Shared authentication helpers. Load after config.js on every page except login.html.

const WRITE_METHODS = ['POST', 'PUT', 'PATCH', 'DELETE'];

// --- Line kiosk (spec 008, FR-005b) ---
// The kiosk launcher opens reader.html#reader=<reader uid>&token=<reader token>. The fragment never reaches the
// server; it is kept for the tab's lifetime (sessionStorage, never localStorage) and removed from the address bar.
const KIOSK_READER_KEY = 'kioskReader';
const KIOSK_TOKEN_KEY = 'kioskToken';
let kioskUnavailable = false;

function sessionGet(key) {
    try {
        return window.sessionStorage.getItem(key);
    } catch (e) {
        return null;
    }
}

(function readKioskFragment() {
    const fragment = new URLSearchParams(window.location.hash.substring(1));
    if (!fragment.has('reader') && !fragment.has('token')) {
        return;
    }
    const reader = fragment.get('reader');
    const token = fragment.get('token');
    if (reader && token) {
        try {
            window.sessionStorage.setItem(KIOSK_READER_KEY, reader);
            window.sessionStorage.setItem(KIOSK_TOKEN_KEY, token);
        } catch (e) {
            console.error('Kiosk mode unavailable: sessionStorage is blocked');
        }
    }
    history.replaceState(null, '', window.location.pathname + window.location.search);
})();

function isKioskMode() {
    return Boolean(sessionGet(KIOSK_TOKEN_KEY));
}

function kioskReader() {
    return sessionGet(KIOSK_READER_KEY);
}

function isKioskUnavailable() {
    return kioskUnavailable;
}

// The reader was disabled or its token changed: nobody can log in from the kiosk, an Administrateur must act.
function showKioskUnavailable() {
    kioskUnavailable = true;
    document.body.innerHTML =
        '<div style="position:fixed;inset:0;background:#000;color:#fff;display:flex;flex-direction:column;' +
        'align-items:center;justify-content:center;font-family:\'Segoe UI\',sans-serif;text-align:center">' +
        '<h1 style="color:#ff4d4d;font-size:3rem;margin:0 0 20px">Kiosque désactivé</h1>' +
        '<p style="font-size:1.5rem;color:#aaa">Contactez un administrateur.</p>' +
        '</div>';
}

function getCookie(name) {
    const prefix = name + '=';
    const cookie = document.cookie.split('; ').find(entry => entry.startsWith(prefix));
    return cookie ? decodeURIComponent(cookie.substring(prefix.length)) : null;
}

function redirectToLogin() {
    const currentPage = window.location.pathname.split('/').pop() + window.location.search;
    window.location.href = 'login.html?next=' + encodeURIComponent(currentPage);
}

// fetch() against the API with the session cookie and the CSRF header, or in kiosk mode with the reader token only.
// options.silent: on 403, return the response without alerting (for background polling).
async function apiFetch(path, options = {}) {
    const { silent, ...fetchOptions } = options;
    const method = (fetchOptions.method || 'GET').toUpperCase();
    const headers = new Headers(fetchOptions.headers || {});
    const kiosk = isKioskMode();
    if (kiosk) {
        headers.set('x-api-token', sessionGet(KIOSK_TOKEN_KEY));
    } else if (WRITE_METHODS.includes(method)) {
        const csrfToken = getCookie('XSRF-TOKEN');
        if (csrfToken) {
            headers.set('X-XSRF-TOKEN', csrfToken);
        }
    }

    const response = await fetch(CONFIG.API_URL + path, {
        ...fetchOptions,
        method,
        headers,
        credentials: kiosk ? 'omit' : 'same-origin'
    });

    if (response.status === 401) {
        if (kiosk) {
            showKioskUnavailable();
        } else {
            redirectToLogin();
        }
    } else if (response.status === 403 && !silent) {
        alert('Accès refusé');
    }
    return response;
}

function showAccessDenied() {
    document.body.innerHTML =
        '<div class="container py-5 text-center">' +
        '<h3 class="text-danger">Accès refusé</h3>' +
        '<p class="text-muted">Votre rôle ne permet pas d\'accéder à cette page.</p>' +
        '<a href="login.html" class="btn btn-outline-secondary">Changer de compte</a>' +
        '</div>';
}

// Resolves with the current user ({username, role}). Redirects to login if not logged in.
// With roles given, shows "Accès refusé" (and never resolves) when the user's role is not among them.
async function requireRole(...roles) {
    const response = await apiFetch('/auth/me', { silent: true });
    if (!response.ok) {
        return new Promise(() => {});
    }
    const user = await response.json();
    if (roles.length > 0 && !roles.includes(user.role)) {
        showAccessDenied();
        return new Promise(() => {});
    }
    // Cosmetic only: the API enforces access. Hide admin-only links from other roles.
    if (user.role !== 'ADMINISTRATEUR') {
        document.querySelectorAll('[data-admin-only]').forEach(element => element.remove());
    }
    return user;
}

async function logout() {
    try {
        await apiFetch('/auth/logout', { method: 'POST', silent: true });
    } finally {
        window.location.href = 'login.html';
    }
}
