package dev.ryvrook.restapi.dashboard;

/**
 * A login link handed out by /restapi dashboard login and not used yet. The token itself is never kept, only its
 * SHA-256 as the store's key.
 *
 * @param who     who asked for it, the session is opened in that name
 * @param expires epoch millis after which it no longer works
 */
public record LoginLink(String who, long expires) {
}
