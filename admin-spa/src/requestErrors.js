// Codes api.js puts on a request that failed in the browser (status 0), so a screen can tell a
// request it stopped waiting for (the backend may have handled it) from one never sent at all.
export const CLIENT_TIMEOUT = 'CLIENT_TIMEOUT'
export const NETWORK_ERROR = 'NETWORK_ERROR'
