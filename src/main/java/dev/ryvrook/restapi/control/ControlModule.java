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
        return out.stream().map(ControlModule::guarded).toList();
    }

    /**
     * The route with engine assertion errors turned into exceptions. The engine asserts the store thread in many
     * places, and an AssertionError is an Error the request pipeline does not catch, so the client would wait
     * for an answer that never comes. As an exception it becomes 500 internal_error with the cause in the log.
     */
    static Route guarded(Route r) {
        Route.Handler h = r.handler();
        return new Route(r.group(), r.method(), r.pattern(), r.scope(), r.access(), r.open(), r.summary(), call -> {
            try {
                return h.handle(call);
            } catch (AssertionError e) {
                throw new IllegalStateException("The engine refused the call from this thread", e);
            }
        }, r.doc());
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
