package dev.ryvrook.restapi.siblings;

import dev.ryvrook.restapi.RestApi;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.Router;
import dev.ryvrook.restapi.util.Versions;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The detection layer. Asks the plugin manager about each suite mod at start, on /restapi reload and every
 * siblings.checkSeconds, decides from siblings.&lt;id&gt; whether its route group should be up, and mounts or
 * unmounts the group as one unit. Listeners hear every change, the OpenAPI document and the log among them.
 *
 * <p>A sibling that was bound and then reloaded brings new classes that the unit already linked against the old
 * ones cannot use. Every suite mod from {@link Sibling#hooksSince()} on reloads its dependents, RestAPI among them,
 * so the group comes back by itself and the unbind in between is logged at the quiet level only. For a sibling
 * without that pass, an older copy bound under always, RestAPI reloads itself through
 * {@link #onStaleLink}.
 */
public final class Siblings {
    private static final class Slot {
        Object identity;
        SiblingUnit unit;
        Presence presence;
        /** The identity a bound unit was linked against, kept after an unbind caused by a reload. */
        Object linkedTo;
    }

    private final PluginProbe probe;
    private final Router router;
    private final Function<Sibling, SiblingUnit> loader;
    private final Function<Sibling, Mode> modes;
    private final Supplier<RestApi> api;
    private final Consumer<String> log;
    private final Consumer<String> quiet;
    private final Map<Sibling, Slot> slots = new EnumMap<>(Sibling.class);
    private final List<Consumer<Presence>> listeners = new CopyOnWriteArrayList<>();
    private volatile Consumer<Sibling> relink;
    private boolean relinkAsked;

    public Siblings(PluginProbe probe, Router router, Function<Sibling, SiblingUnit> loader,
                    Function<Sibling, Mode> modes, Supplier<RestApi> api, Consumer<String> log) {
        this(probe, router, loader, modes, api, log, line -> { });
    }

    /**
     * @param quiet where the lines go that a sibling reload causes on its way to reloading RestAPI anyway, the
     *              debug level of the plugin logger
     */
    public Siblings(PluginProbe probe, Router router, Function<Sibling, SiblingUnit> loader,
                    Function<Sibling, Mode> modes, Supplier<RestApi> api, Consumer<String> log, Consumer<String> quiet) {
        this.quiet = quiet;
        this.probe = probe;
        this.router = router;
        this.loader = loader;
        this.modes = modes;
        this.api = api;
        this.log = log;
        for (Sibling s : Sibling.values()) {
            Slot slot = new Slot();
            slot.presence = new Presence(s, modes.apply(s), false, null, null, false, 0, "not checked yet");
            slots.put(s, slot);
        }
    }

    /** Loads a unit class by name with the plugin's class loader. Null when this build has no such class. */
    public static Function<Sibling, SiblingUnit> byName(ClassLoader loader) {
        return s -> {
            try {
                Class<?> c = Class.forName(s.unitClass(), true, loader);
                return (SiblingUnit) c.getDeclaredConstructor().newInstance();
            } catch (ClassNotFoundException e) {
                return null;
            } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
                throw new IllegalStateException(s.unitClass() + " could not be made, " + e, e);
            }
        };
    }

    public void listen(Consumer<Presence> listener) {
        listeners.add(listener);
    }

    /**
     * What reloads RestAPI when a bound sibling was reloaded and that version has no dependents pass, an older
     * copy bound under always. Asked once per RestAPI instance, the next instance binds the new classes on its own.
     */
    public void onStaleLink(Consumer<Sibling> relink) {
        this.relink = relink;
    }

    /** Checks every sibling and binds or unbinds where the answer changed. */
    public synchronized void check() {
        for (Sibling s : Sibling.values()) {
            check(s);
        }
    }

    /** The table for /restapi status and /api/server, in declaration order. */
    public synchronized List<Presence> table() {
        List<Presence> out = new ArrayList<>();
        for (Sibling s : Sibling.values()) {
            out.add(slots.get(s).presence);
        }
        return out;
    }

    public synchronized Presence presence(Sibling s) {
        return slots.get(s).presence;
    }

    /** Unbinds everything, at shutdown. */
    public synchronized void close() {
        for (Sibling s : Sibling.values()) {
            Slot slot = slots.get(s);
            if (slot.unit != null) {
                unbind(s, slot);
            }
        }
    }

    private void check(Sibling s) {
        Slot slot = slots.get(s);
        Mode mode = modes.apply(s);
        PluginProbe.Found found = probe.find(s);
        Object identity = found == null ? null : found.identity();
        boolean reloaded = slot.linkedTo != null && identity != null && identity != slot.linkedTo;

        String why = null;
        String note = null;
        if (mode == Mode.NEVER) {
            why = "siblings." + s.id() + " is never";
        } else if (found == null) {
            why = "not installed";
        } else if (!found.running()) {
            why = "its state is " + found.state();
        } else if (Versions.below(found.version(), s.minimum())) {
            if (mode == Mode.ALWAYS) {
                note = "version " + found.version() + " is older than " + s.minimum() + ", bound because of always";
            } else {
                why = "version " + found.version() + " is older than " + s.minimum();
            }
        }
        boolean passes = found != null && s.reloadsDependents(found.version());
        Consumer<Sibling> self = relink;
        boolean comesBack = passes || found != null && self != null;
        if (why == null && reloaded) {
            if (passes) {
                why = s.pluginName() + " was reloaded and reloads RestAPI in a moment, which binds it again";
            } else if (self != null) {
                why = s.pluginName() + " was reloaded, RestAPI reloads itself in a moment to bind it again";
                if (!relinkAsked) {
                    relinkAsked = true;
                    self.accept(s);
                }
            } else {
                why = s.pluginName() + " was reloaded while RestAPI ran. Run /plugin reload Ryvrook:RestAPI to bind it again";
            }
        }
        // A sibling that is stopping or was reloaded and reloads its dependents brings RestAPI back on its own.
        boolean passing = comesBack && slot.linkedTo != null && (reloaded || !found.running());

        if (slot.unit != null && (why != null || identity != slot.identity)) {
            unbind(s, slot);
        }
        slot.identity = identity;

        if (why == null && slot.unit == null) {
            why = bind(s, slot, note);
        }
        int routes = slot.unit == null ? 0 : (int) router.routes().stream().filter(r -> r.group().equals(s.group())).count();
        Presence next = new Presence(s, mode, found != null, found == null ? null : found.version(),
                found == null ? null : found.state(), slot.unit != null, routes, slot.unit != null ? note : why);
        if (!next.equals(slot.presence)) {
            Presence before = slot.presence;
            slot.presence = next;
            boolean alwaysMissing = mode == Mode.ALWAYS && !next.bound()
                    && !java.util.Objects.equals(before.reason(), next.reason());
            if (before.bound() != next.bound() || alwaysMissing) {
                (passing ? quiet : log).accept(describe(next));
            }
            for (Consumer<Presence> l : listeners) {
                try {
                    l.accept(next);
                } catch (RuntimeException e) {
                    log.accept("A sibling listener failed, " + e);
                }
            }
        }
    }

    /** Binds the unit. Returns null when bound, else the reason. */
    private String bind(Sibling s, Slot slot, String note) {
        SiblingUnit unit;
        try {
            unit = loader.apply(s);
        } catch (RuntimeException e) {
            return e.getMessage();
        }
        if (unit == null) {
            return "this build of RestAPI has no routes for it yet";
        }
        List<Route> routes;
        try {
            routes = unit.bind(api.get());
        } catch (Exception | LinkageError e) {
            return "binding failed, " + e;
        }
        for (Route r : routes) {
            boolean scopeOk = r.scope() == s.scope() || s.writeScope() != null && r.scope() == s.writeScope();
            if (!r.group().equals(s.group()) || !scopeOk || !r.pattern().startsWith("/api/" + s.id() + "/")
                    && !r.pattern().equals("/api/" + s.id())) {
                unit.unbind();
                return "route " + r.method() + " " + r.pattern() + " is outside /api/" + s.id()
                        + ", group " + s.group() + " or scope " + s.scope().id();
            }
        }
        try {
            router.mount(s.group(), routes);
        } catch (RuntimeException e) {
            unit.unbind();
            return e.getMessage();
        }
        slot.unit = unit;
        slot.linkedTo = slot.identity;
        return null;
    }

    private void unbind(Sibling s, Slot slot) {
        try {
            slot.unit.unbind();
        } catch (RuntimeException | LinkageError e) {
            log.accept("RestAPI could not unbind " + s.pluginName() + " cleanly, " + e);
        }
        router.unmount(s.group());
        slot.unit = null;
    }

    /** One log line for a presence. */
    public static String describe(Presence p) {
        Sibling s = p.sibling();
        if (p.bound()) {
            return "RestAPI bound " + s.pluginName() + " " + p.version() + ", " + p.routes() + " routes under /api/"
                    + s.id() + (p.reason() == null ? "" : " (" + p.reason() + ")");
        }
        return "RestAPI has no " + s.pluginName() + " routes, " + p.reason();
    }
}
