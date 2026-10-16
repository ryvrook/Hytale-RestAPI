package dev.ryvrook.restapi.command;

import java.util.List;

/**
 * The permission nodes of /restapi. The console holds every node. Keys are a separate node, so staff who may read
 * the status cannot make themselves a key.
 */
public final class Perms {
    private Perms() {
    }

    /** status, reload and audit tail. */
    public static final String ADMIN = "restapi.admin";
    /** keys list, create and revoke. */
    public static final String KEYS = "restapi.admin.keys";

    public static final List<String> NODES = List.of(ADMIN, KEYS);
}
