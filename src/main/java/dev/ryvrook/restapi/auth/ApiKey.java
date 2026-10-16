package dev.ryvrook.restapi.auth;

import java.util.List;
import java.util.Set;

/**
 * One named key as keys.json stores it. The secret itself is never kept, only the SHA-256 of the whole token.
 *
 * @param name       the name an admin gave it, shown in the audit log and in /restapi keys list
 * @param id         eight hex digits that are part of the token and say which key a request claims to be
 * @param hash       SHA-256 of the whole token, in hex
 * @param scopes     what the key may do
 * @param created    when it was made, as an ISO instant
 * @param createdBy  who made it, the console, a player name or first start
 * @param perMinute  its own request limit per minute, or -1 for limits.requestsPerMinute
 * @param allowFrom  addresses or CIDR ranges the key is accepted from, empty for any
 */
public record ApiKey(String name, String id, String hash, Set<Scope> scopes, String created, String createdBy,
                     int perMinute, List<String> allowFrom) {
    public ApiKey {
        scopes = Set.copyOf(scopes);
        allowFrom = allowFrom == null ? List.of() : List.copyOf(allowFrom);
    }

    public boolean has(Scope scope) {
        return scope != null && scopes.contains(scope);
    }
}
