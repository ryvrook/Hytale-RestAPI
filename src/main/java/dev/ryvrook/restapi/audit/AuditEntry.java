package dev.ryvrook.restapi.audit;

/**
 * One line of the audit log.
 *
 * @param time    ISO instant
 * @param source  http, rcon or command
 * @param key     the key name, a player or console for /restapi, or "unknown" for a failed login
 * @param client  the client address, or the player name for an in game command
 * @param action  method and path for HTTP, such as {@code POST /api/players/Steve/kick}, or the command words
 * @param status  the HTTP status, or 0 where there is none
 * @param detail  a short note, such as the scope that was missing, never a token or a body
 */
public record AuditEntry(String time, String source, String key, String client, String action, int status,
                         String detail) {
}
