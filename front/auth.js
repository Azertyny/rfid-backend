// Shared authentication helpers. Load after config.js on every page except login.html.

const WRITE_METHODS = ['POST', 'PUT', 'PATCH', 'DELETE'];

function getCookie(name) {
    const prefix = name + '=';
    const cookie = document.cookie.split('; ').find(entry => entry.startsWith(prefix));
    return cookie ? decodeURIComponent(cookie.substring(prefix.length)) : null;
}

function redirectToLogin() {
    const currentPage = window.location.pathname.split('/').pop() + window.location.search;
    window.location.href = 'login.html?next=' + encodeURIComponent(currentPage);
}

// fetch() against the API with the session cookie and the CSRF header.
// options.silent: on 403, return the response without alerting (for background polling).
async function apiFetch(path, options = {}) {
    const { silent, ...fetchOptions } = options;
    const method = (fetchOptions.method || 'GET').toUpperCase();
    const headers = new Headers(fetchOptions.headers || {});
    if (WRITE_METHODS.includes(method)) {
        const csrfToken = getCookie('XSRF-TOKEN');
        if (csrfToken) {
            headers.set('X-XSRF-TOKEN', csrfToken);
        }
    }

    const response = await fetch(CONFIG.API_URL + path, {
        ...fetchOptions,
        method,
        headers,
        credentials: 'same-origin'
    });

    if (response.status === 401) {
        redirectToLogin();
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
