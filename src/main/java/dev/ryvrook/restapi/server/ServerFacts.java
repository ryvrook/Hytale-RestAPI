package dev.ryvrook.restapi.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.common.util.java.ManifestUtil;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.HytaleServerConfig;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Engine reads for /api/server and /api/health. Every call here is safe off the world threads, see
 * docs/DEVELOPMENT.md. Each section is filled on its own, so one engine change breaks one section, not the answer.
 */
public final class ServerFacts {
    private ServerFacts() {
    }

    /**
     * booting, running or stopping. isBooting() stays true after the boot on 0.7.1, the flag is set once and never
     * cleared, so only isBooted() and isShuttingDown() are read.
     */
    public static String phase() {
        HytaleServer s = HytaleServer.get();
        if (s == null) {
            return "booting";
        }
        if (s.isShuttingDown()) {
            return "stopping";
        }
        return s.isBooted() ? "running" : "booting";
    }

    public static JsonObject server() {
        JsonObject o = new JsonObject();
        HytaleServer s = HytaleServer.get();
        o.addProperty("version", ManifestUtil.getVersion());
        o.addProperty("revision", ManifestUtil.getImplementationRevisionId());
        o.addProperty("patchline", ManifestUtil.getPatchline());
        o.addProperty("phase", phase());
        if (s != null) {
            HytaleServerConfig c = s.getConfig();
            if (c != null) {
                o.addProperty("name", c.getServerName());
                o.addProperty("motd", c.getMotd());
                o.addProperty("maxPlayers", c.getMaxPlayers());
                o.addProperty("maxViewRadius", c.getMaxViewRadius());
                o.addProperty("passwordSet", c.getPassword() != null && !c.getPassword().isEmpty());
            }
            Instant boot = s.getBoot();
            if (boot != null) {
                o.addProperty("bootedAt", boot.toString());
                o.addProperty("uptimeSeconds", Math.max(0, Instant.now().getEpochSecond() - boot.getEpochSecond()));
            }
        }
        return o;
    }

    public static JsonObject players() {
        JsonObject o = new JsonObject();
        Universe u = Universe.get();
        JsonArray list = new JsonArray();
        if (u != null) {
            List<PlayerRef> refs = new ArrayList<>(u.getPlayers());
            refs.sort(Comparator.comparing(p -> String.valueOf(p.getUsername()), String.CASE_INSENSITIVE_ORDER));
            for (PlayerRef p : refs) {
                JsonObject e = new JsonObject();
                e.addProperty("name", p.getUsername());
                e.addProperty("uuid", String.valueOf(p.getUuid()));
                UUID worldId = p.getWorldUuid();
                World w = worldId == null ? null : u.getWorld(worldId);
                e.addProperty("world", w == null ? null : w.getName());
                list.add(e);
            }
            o.addProperty("online", u.getPlayerCount());
        }
        o.add("list", list);
        return o;
    }

    public static JsonObject jvm() {
        JsonObject o = new JsonObject();
        Runtime rt = Runtime.getRuntime();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        o.addProperty("java", Runtime.version().toString());
        o.addProperty("cpus", rt.availableProcessors());
        o.addProperty("heapUsedMb", heap.getUsed() / (1024 * 1024));
        o.addProperty("heapCommittedMb", heap.getCommitted() / (1024 * 1024));
        o.addProperty("heapMaxMb", heap.getMax() < 0 ? -1 : heap.getMax() / (1024 * 1024));
        o.addProperty("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        o.addProperty("systemLoad", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            o.addProperty("processCpu", round(os.getProcessCpuLoad()));
        }
        o.addProperty("jvmUptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        return o;
    }

    static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
