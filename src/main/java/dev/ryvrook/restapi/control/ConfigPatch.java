package dev.ryvrook.restapi.control;

import com.google.gson.JsonObject;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.text.Messages;

/**
 * The body of PATCH /api/server/config, read and checked before anything touches config.json. Every field is
 * optional, a body that names none of them answers 400.
 */
record ConfigPatch(String name, String motd, Integer maxPlayers, Integer maxViewRadius, Boolean requireJoinPermission) {
    static final String FIELDS = "name, motd, maxPlayers, maxViewRadius, requireJoinPermission";

    static ConfigPatch parse(JsonObject body, int viewRadiusLimit) {
        ConfigPatch p = new ConfigPatch(
                Bodies.optText(body, "name", 128),
                Bodies.optText(body, "motd", 512),
                Bodies.optInt(body, "maxPlayers", 1, 100_000),
                Bodies.optInt(body, "maxViewRadius", 1, Math.max(1, viewRadiusLimit)),
                Bodies.optBool(body, "requireJoinPermission"));
        if (p.name == null && p.motd == null && p.maxPlayers == null && p.maxViewRadius == null && p.requireJoinPermission == null) {
            throw ApiException.badRequest("bad_value", Messages.get(ControlText.NOTHING, "fields", FIELDS));
        }
        if (p.name != null && p.name.isBlank()) {
            throw Bodies.bad("name", Messages.get(ControlText.MUST_TEXT, "max", 128));
        }
        return p;
    }
}
