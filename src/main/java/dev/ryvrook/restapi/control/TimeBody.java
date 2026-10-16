package dev.ryvrook.restapi.control;

import com.google.gson.JsonObject;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.text.Messages;

/** The body of PUT /api/worlds/{world}/time, hour 0 to 24 or dayProgress 0 to 1, exactly one of them. */
final class TimeBody {
    private TimeBody() {
    }

    /** The day progress from 0 to 1 that WorldTimeResource.setDayTime takes. /time divides the hour by 24 the same way. */
    static double dayProgress(JsonObject body) {
        Double hour = Bodies.optNumber(body, "hour", 0, 24);
        Double progress = Bodies.optNumber(body, "dayProgress", 0, 1);
        if ((hour == null) == (progress == null)) {
            throw ApiException.badRequest("bad_value", Messages.get(ControlText.BAD_VALUE, "field", "The body",
                    "problem", Messages.get(ControlText.MUST_ONE_OF, "fields", "hour, dayProgress")));
        }
        return hour != null ? hour / 24.0 : progress;
    }
}
