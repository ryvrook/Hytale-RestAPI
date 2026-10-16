package dev.ryvrook.restapi.control;

import dev.ryvrook.restapi.RestApi;
import dev.ryvrook.restapi.RestModule;
import dev.ryvrook.restapi.config.Setting;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.text.Messages;

import java.util.ArrayList;
import java.util.List;

/**
 * Plugins, worlds and server control, the routes under /api/plugins, /api/worlds and /api/server/... other than
 * GET /api/server itself, which stays in the core module. control.enabled false mounts none of them.
 *
 * <p>State that must outlive a /restapi reload lives here, since the module object is kept across reloads: the
 * pending stop, the backup jobs and the idempotency answers.
 */
public final class ControlModule implements RestModule {
    public static final String GROUP = "control";

    private final Replays replays = new Replays(System::currentTimeMillis, ControlSettings.IDEMPOTENCY_SECONDS::get);
    private final BackupJobs backups = new BackupJobs(ControlSettings.BACKUP_JOBS_KEPT::get);
    private volatile Stopper stopper;

    public ControlModule() {
        ControlSettings.ENABLED.key();
        ControlText.OFF.length();
    }

    @Override
    public String group() {
        return GROUP;
    }

    @Override
    public List<Route> routes(RestApi api) {
        if (!ControlSettings.ENABLED.get()) {
            return List.of();
        }
        if (stopper == null && api != null) {
            stopper = ServerRoutes.newStopper(api);
        }
        List<Route> out = new ArrayList<>();
        out.addAll(PluginRoutes.routes(this));
        out.addAll(WorldRoutes.routes(this, api));
        out.addAll(ServerRoutes.routes(this, api));
        return out;
    }

    Replays replays() {
        return replays;
    }

    BackupJobs backups() {
        return backups;
    }

    Stopper stopper() {
        return stopper;
    }

    /** Answers 403 feature_off naming the setting when it is false. */
    static void require(Setting<Boolean> s) {
        if (!s.get()) {
            throw new ApiException(403, "feature_off", Messages.get(ControlText.OFF, "setting", s.key()));
        }
    }
}
