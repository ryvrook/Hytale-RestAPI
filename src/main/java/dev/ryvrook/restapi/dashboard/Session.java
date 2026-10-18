package dev.ryvrook.restapi.dashboard;

/**
 * One dashboard login. The cookie value itself is never kept, only its SHA-256 as the store's key.
 *
 * @param digest  SHA-256 of the cookie value in hex, the key in the store
 * @param handle  eight hex digits that name the session in the rate limiter, not a secret
 * @param who     who ran /restapi dashboard login, a player name or console
 * @param created when it was opened, epoch millis
 */
public record Session(String digest, String handle, String who, long created) {
    /** The name the audit log and the request limiter know it by. */
    public String principal() {
        return "dashboard:" + who;
    }
}
