package dev.ryvrook.restapi.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.HytaleServerConfig;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ShutdownReason;
import com.hypixel.hytale.server.core.config.BackupConfig;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.ryvrook.restapi.RestApi;
import dev.ryvrook.restapi.audit.AuditEntry;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.http.Call;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.RouteDoc;
import dev.ryvrook.restapi.text.Messages;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * /api/server/config, /save, /backup and /stop. GET /api/server itself belongs to the core module. config.json
 * changes go through the engine's own setters and HytaleServerConfig.save, the same calls its commands make.
 */
final class ServerRoutes {
    private ServerRoutes() {
    }

    private static final String G = ControlModule.GROUP;

    static List<Route> routes(ControlModule m, RestApi api) {
        Replays r = m.replays();
        return List.of(
                Route.of(G, "GET", "/api/server/config", Scope.READ, Access.READ,
                        "The parts of config.json an admin changes most: name, MOTD, player and view limits, password set or not, the join permission switch, the default world, backups and log levels.",
                        call -> Response.ok(config())).doc(new RouteDoc(List.of(), null,
                        "{\"name\":\"My server\",\"motd\":\"Welcome\",\"maxPlayers\":20,\"maxViewRadius\":12,\"passwordSet\":false,"
                                + "\"requireJoinPermission\":false,\"defaultWorld\":\"default\",\"backup\":{\"configured\":false},\"logLevels\":{}}",
                        List.of())),
                Route.of(G, "PATCH", "/api/server/config", Scope.SERVER, Access.WRITE,
                        "Changes name, motd, maxPlayers, maxViewRadius or requireJoinPermission and saves config.json. Needs control.allowConfigEdit.",
                        call -> patchConfig(call)).doc(new RouteDoc(List.of(), "{\"motd\":\"Back at 18:00\",\"maxPlayers\":40}",
                        "{\"name\":\"My server\",\"motd\":\"Back at 18:00\",\"maxPlayers\":40}",
                        List.of("bad_value", "feature_off", "save_failed"))),
                Route.of(G, "POST", "/api/server/save", Scope.SERVER, Access.WRITE,
                        "Saves every world, or the ones named in worlds, and answers per world. 202 when one is still saving after control.saveTimeoutSeconds.",
                        call -> saveAll(call)).doc(new RouteDoc(List.of(), "{\"worlds\":[\"default\"]}",
                        "{\"worlds\":[{\"world\":\"default\",\"state\":\"saved\",\"millis\":201}]}", List.of("world_not_found"))),
                Route.of(G, "POST", "/api/server/backup", Scope.SERVER, Access.WRITE,
                        "Starts a backup zip in the configured backup folder and answers 202 with a job id. 409 when no backup folder is set or one runs.",
                        call -> backup(m, call)).doc(new RouteDoc(List.of(), null,
                        "{\"job\":\"backup-1a2b3c4d\",\"state\":\"running\",\"startedAt\":\"2026-10-16T12:00:00Z\"}",
                        List.of("backup_not_configured", "backup_running", "booting"))),
                Route.of(G, "GET", "/api/server/backup/{job}", Scope.SERVER, Access.READ,
                        "A backup job, running, done with the zip file or failed with the reason.",
                        call -> job(m, call)).doc(new RouteDoc(List.of(), null,
                        "{\"job\":\"backup-1a2b3c4d\",\"state\":\"done\",\"file\":\"backups/2026-10-16_12-00-00.zip\",\"millis\":5400}",
                        List.of("job_not_found"))),
                Route.of(G, "GET", "/api/server/stop", Scope.SERVER, Access.READ,
                        "The stop that is counting down, or pending false.",
                        call -> pending(m)).doc(new RouteDoc(List.of(), null,
                        "{\"pending\":true,\"stop\":\"stop-1a2b3c4d\",\"secondsLeft\":42,\"exitCode\":8,\"restart\":true}", List.of())),
                Route.of(G, "POST", "/api/server/stop", Scope.SERVER, Access.DANGEROUS,
                        "Stops the server after delaySeconds with the exit code given. restart true uses control.restartExitCode for a supervisor that starts the server again. Needs confirm stop and control.allowStop.",
                        r.wrap(call -> stop(m, api, call))).doc(new RouteDoc(List.of(),
                        "{\"confirm\":\"stop\",\"delaySeconds\":60,\"restart\":true,\"reason\":\"Update to 0.7.2\",\"announce\":true}",
                        "{\"stop\":\"stop-1a2b3c4d\",\"at\":\"2026-10-16T12:01:00Z\",\"secondsLeft\":60,\"exitCode\":8,\"restart\":true}",
                        List.of("confirm_required", "stop_pending", "stopping", "feature_off", "bad_value"))),
                Route.of(G, "POST", "/api/server/stop/cancel", Scope.SERVER, Access.WRITE,
                        "Calls off the stop that is counting down and tells the players when it was announced.",
                        call -> cancel(m, api, call)).doc(new RouteDoc(List.of(), null,
                        "{\"cancelled\":true,\"stop\":\"stop-1a2b3c4d\"}", List.of("no_stop_pending"))));
    }

    private static HytaleServerConfig serverConfig() {
        HytaleServer s = HytaleServer.get();
        HytaleServerConfig c = s == null ? null : s.getConfig();
        if (c == null) {
            throw new ApiException(503, "booting", Messages.get(ControlText.BOOTING));
        }
        return c;
    }

    private static JsonObject config() {
        HytaleServerConfig c = serverConfig();
        JsonObject o = new JsonObject();
        o.addProperty("name", c.getServerName());
        o.addProperty("motd", c.getMotd());
        o.addProperty("maxPlayers", c.getMaxPlayers());
        o.addProperty("maxViewRadius", c.getMaxViewRadius());
        o.addProperty("maxViewRadiusLimit", HytaleServerConfig.MAX_VIEW_RADIUS_LIMIT);
        o.addProperty("passwordSet", c.getPassword() != null && !c.getPassword().isEmpty());
        o.addProperty("requireJoinPermission", c.isRequireJoinPermission());
        o.addProperty("defaultWorld", c.getDefaults() == null ? null : c.getDefaults().getWorld());
        o.addProperty("defaultGameMode", c.getDefaults() == null || c.getDefaults().getGameMode() == null
                ? null : c.getDefaults().getGameMode().name());
        JsonObject backup = new JsonObject();
        BackupConfig b = c.getBackupConfig();
        if (b != null) {
            backup.addProperty("configured", b.isConfigured());
            backup.addProperty("enabled", b.isEnabled());
            backup.addProperty("directory", b.getDirectory() == null ? null : b.getDirectory().toString());
            backup.addProperty("frequencyMinutes", b.getFrequencyMinutes());
            backup.addProperty("maxCount", b.getMaxCount());
            backup.addProperty("archiveMaxCount", b.getArchiveMaxCount());
        } else {
            backup.addProperty("configured", false);
        }
        o.add("backup", backup);
        JsonObject levels = new JsonObject();
        Map<String, Level> lv = c.getLogLevels();
        if (lv != null) {
            new TreeMap<>(lv).forEach((k, v) -> levels.addProperty(k, v == null ? null : v.getName()));
        }
        o.add("logLevels", levels);
        return o;
    }

    private static Response patchConfig(Call call) {
        ControlModule.require(ControlSettings.ALLOW_CONFIG_EDIT);
        ConfigPatch p = ConfigPatch.parse(call.jsonBody(), HytaleServerConfig.MAX_VIEW_RADIUS_LIMIT);
        HytaleServerConfig c = serverConfig();
        if (p.name() != null) {
            c.setServerName(p.name().trim());
        }
        if (p.motd() != null) {
            c.setMotd(p.motd());
        }
        if (p.maxPlayers() != null) {
            c.setMaxPlayers(p.maxPlayers());
        }
        if (p.maxViewRadius() != null) {
            c.setMaxViewRadius(p.maxViewRadius());
        }
        if (p.requireJoinPermission() != null) {
            c.setRequireJoinPermission(p.requireJoinPermission());
        }
        c.markChanged();
        try {
            HytaleServerConfig.save(c).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new ApiException(500, "save_failed", Messages.get(ControlText.SAVE_FAILED));
        }
        return Response.ok(config());
    }

    private static Response saveAll(Call call) {
        List<String> names = Bodies.optNames(call.jsonBody(), "worlds");
        List<World> worlds = new ArrayList<>();
        if (names == null) {
            Universe u = Universe.get();
            if (u == null) {
                throw new ApiException(503, "booting", Messages.get(ControlText.BOOTING));
            }
            for (World w : u.getWorlds().values()) {
                if (w != null && w.isAlive()) {
                    worlds.add(w);
                }
            }
        } else {
            for (String n : names) {
                worlds.add(WorldRoutes.world(n));
            }
        }
        long start = System.currentTimeMillis();
        long deadline = start + TimeUnit.SECONDS.toMillis(ControlSettings.SAVE_TIMEOUT_SECONDS.get());
        Map<String, CompletableFuture<Void>> running = new LinkedHashMap<>();
        for (World w : worlds) {
            CompletableFuture<Void> f;
            try {
                f = WorldRoutes.saveWorld(w);
            } catch (RuntimeException e) {
                f = CompletableFuture.failedFuture(e);
            }
            running.put(w.getName(), f);
        }
        JsonArray out = new JsonArray();
        boolean pending = false;
        for (Map.Entry<String, CompletableFuture<Void>> e : running.entrySet()) {
            JsonObject one = WorldRoutes.awaitSave(e.getKey(), e.getValue(), start, deadline);
            pending |= "running".equals(one.get("state").getAsString());
            out.add(one);
        }
        JsonObject o = new JsonObject();
        o.add("worlds", out);
        o.addProperty("millis", System.currentTimeMillis() - start);
        return Response.json(pending ? 202 : 200, o);
    }

    private static Response backup(ControlModule m, Call call) {
        HytaleServer s = HytaleServer.get();
        if (s == null || !s.isBooted()) {
            throw new ApiException(409, "booting", Messages.get(ControlText.BOOTING));
        }
        BackupConfig b = serverConfig().getBackupConfig();
        Path dir = b == null ? null : b.getDirectory();
        if (dir == null) {
            throw new ApiException(409, "backup_not_configured", Messages.get(ControlText.BACKUP_DIR));
        }
        BackupJobs.Job job = m.backups().start(call.keyName(), () -> Universe.get().runBackup(), () -> newest(dir));
        if (job == null) {
            BackupJobs.Job running = m.backups().running();
            throw new ApiException(409, "backup_running", Messages.get(ControlText.BACKUP_RUNNING, "job",
                    running == null ? "?" : running.id));
        }
        return Response.json(202, job.json()).with("Location", "/api/server/backup/" + job.id);
    }

    /** The newest file under the backup folder, two levels deep. */
    static String newest(Path dir) {
        try (Stream<Path> s = Files.walk(dir, 2)) {
            Path best = null;
            FileTime bestTime = null;
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                FileTime t = Files.getLastModifiedTime(p);
                if (bestTime == null || t.compareTo(bestTime) > 0) {
                    best = p;
                    bestTime = t;
                }
            }
            return best == null ? null : best.toString();
        } catch (IOException e) {
            return null;
        }
    }

    private static Response job(ControlModule m, Call call) {
        BackupJobs.Job job = m.backups().get(call.param("job"));
        if (job == null) {
            throw ApiException.notFound("job_not_found", Messages.get(ControlText.JOB_NOT_FOUND, "job", call.param("job")));
        }
        return Response.ok(job.json());
    }

    private static Response pending(ControlModule m) {
        Stopper.Plan p = m.stopper().pending();
        JsonObject o = p == null ? new JsonObject() : p.json(System.currentTimeMillis());
        o.addProperty("pending", p != null);
        return Response.ok(o);
    }

    private static Response stop(ControlModule m, RestApi api, Call call) {
        ControlModule.require(ControlSettings.ALLOW_STOP);
        HytaleServer s = HytaleServer.get();
        if (s != null && s.isShuttingDown()) {
            throw new ApiException(409, "stopping", Messages.get(ControlText.STOPPING));
        }
        StopBody b = StopBody.parse(call.jsonBody(), ControlSettings.STOP_DELAY_SECONDS.get(),
                ControlSettings.STOP_MAX_DELAY_SECONDS.get(), ControlSettings.STOP_ANNOUNCE.get(),
                ControlSettings.RESTART_EXIT_CODE.get());
        Stopper.Plan plan = m.stopper().schedule(b.delaySeconds(), b.exitCode(), b.restart(), b.reason(), b.announce(),
                call.keyName(), call.client());
        if (plan == null) {
            Stopper.Plan p = m.stopper().pending();
            throw new ApiException(409, "stop_pending", Messages.get(ControlText.STOP_PENDING, "at",
                    p == null ? "?" : Instant.ofEpochMilli(p.atMillis()).toString()));
        }
        api.logger().at(Level.INFO).log("RestAPI key %s from %s asked for a stop in %d seconds with exit code %d",
                call.keyName(), call.client(), b.delaySeconds(), b.exitCode());
        return Response.json(202, plan.json(System.currentTimeMillis()));
    }

    private static Response cancel(ControlModule m, RestApi api, Call call) {
        Stopper.Plan p = m.stopper().cancel();
        if (p == null) {
            throw ApiException.notFound("no_stop_pending", Messages.get(ControlText.NO_STOP));
        }
        if (p.announce()) {
            broadcast(Messages.get(ControlText.STOP_CANCELLED));
        }
        api.logger().at(Level.INFO).log("RestAPI key %s called off stop %s", call.keyName(), p.id());
        JsonObject o = new JsonObject();
        o.addProperty("cancelled", true);
        o.addProperty("stop", p.id());
        return Response.ok(o);
    }

    private static void broadcast(String text) {
        try {
            Universe u = Universe.get();
            if (u != null) {
                u.sendMessage(Message.raw(text).color("#e0b050"));
            }
        } catch (RuntimeException | LinkageError ignored) {
            // No players to tell.
        }
    }

    /** The countdown with its chat lines and the shutdown on a thread of its own. */
    static Stopper newStopper(RestApi api) {
        return new Stopper(api.scheduler(), System::currentTimeMillis, new Stopper.Hooks() {
            @Override
            public void announce(Stopper.Plan plan, int secondsLeft) {
                String line = secondsLeft <= 0
                        ? Messages.get(plan.restart() ? ControlText.RESTART_NOW : ControlText.STOP_NOW)
                        : Messages.get(plan.restart() ? ControlText.RESTART_IN : ControlText.STOP_IN,
                        "time", ControlText.duration(secondsLeft));
                broadcast(line);
                if (plan.reason() != null && secondsLeft == plan.delaySeconds()) {
                    broadcast(plan.reason());
                }
            }

            @Override
            public void fire(Stopper.Plan plan) {
                api.audit().record(new AuditEntry(Instant.now().toString(), "http", plan.key(), plan.client(),
                        "server stop", 0, plan.id() + " exit code " + plan.exitCode()));
                api.logger().at(Level.INFO).log("RestAPI stops the server now with exit code %d, asked for by key %s",
                        plan.exitCode(), plan.key());
                String kick = plan.reason() != null ? plan.reason()
                        : Messages.get(plan.restart() ? ControlText.RESTART_KICK : ControlText.STOP_KICK);
                ShutdownReason reason = (plan.exitCode() == 0 ? ShutdownReason.SHUTDOWN
                        : new ShutdownReason(plan.exitCode(), plan.restart() ? "restart" : "restapi"))
                        .withMessage(Message.raw(kick));
                Thread t = new Thread(() -> {
                    try {
                        HytaleServer.get().shutdownServer(reason);
                    } catch (RuntimeException | LinkageError e) {
                        api.logger().at(Level.SEVERE).withCause(e).log("RestAPI could not stop the server");
                    }
                }, "RestAPI-stop");
                t.setDaemon(false);
                t.start();
            }
        });
    }
}
