package dev.ryvrook.restapi.control;

import com.google.gson.JsonObject;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.text.Messages;

/**
 * The body of POST /api/server/stop. confirm must be the word stop, so a stray POST never stops a server.
 * restart true picks control.restartExitCode unless exitCode is given too.
 */
record StopBody(int exitCode, boolean restart, String reason, int delaySeconds, boolean announce) {
    static StopBody parse(JsonObject body, int defaultDelay, int maxDelay, boolean defaultAnnounce, int restartCode) {
        String confirm = Bodies.optText(body, "confirm", 16);
        if (!"stop".equals(confirm)) {
            throw ApiException.badRequest("confirm_required", Messages.get(ControlText.CONFIRM));
        }
        Integer code = Bodies.optInt(body, "exitCode", 0, 255);
        boolean restart = Boolean.TRUE.equals(Bodies.optBool(body, "restart"));
        String reason = Bodies.optText(body, "reason", 256);
        Integer delay = Bodies.optInt(body, "delaySeconds", 0, maxDelay);
        Boolean announce = Bodies.optBool(body, "announce");
        int exit = code != null ? code : restart ? restartCode : 0;
        return new StopBody(exit, restart || (code != null && code == restartCode && code != 0),
                reason == null || reason.isBlank() ? null : reason.trim(),
                delay != null ? delay : Math.min(defaultDelay, maxDelay),
                announce != null ? announce : defaultAnnounce);
    }
}
