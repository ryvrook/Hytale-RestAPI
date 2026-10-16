package dev.ryvrook.restapi.suite.coinage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ryvrook.coinage.api.Account;
import dev.ryvrook.coinage.api.AccountType;
import dev.ryvrook.coinage.api.Accounts;
import dev.ryvrook.coinage.api.CoinageApi;
import dev.ryvrook.coinage.api.Currencies;
import dev.ryvrook.coinage.api.Currency;
import dev.ryvrook.coinage.api.DailyReport;
import dev.ryvrook.coinage.api.Failure;
import dev.ryvrook.coinage.api.Initiator;
import dev.ryvrook.coinage.api.NetWorth;
import dev.ryvrook.coinage.api.Query;
import dev.ryvrook.coinage.api.Rank;
import dev.ryvrook.coinage.api.Request;
import dev.ryvrook.coinage.api.Result;
import dev.ryvrook.coinage.api.Stats;
import dev.ryvrook.coinage.api.Transaction;
import dev.ryvrook.restapi.RestApi;
import dev.ryvrook.restapi.audit.AuditEntry;
import dev.ryvrook.restapi.audit.AuditSink;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.http.Call;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.RouteDoc;
import dev.ryvrook.restapi.siblings.Sibling;
import dev.ryvrook.restapi.siblings.SiblingUnit;
import dev.ryvrook.restapi.suite.Json;
import dev.ryvrook.restapi.suite.Limits;
import dev.ryvrook.restapi.suite.Names;
import dev.ryvrook.restapi.suite.Replays;
import dev.ryvrook.restapi.suite.SuiteSettings;
import dev.ryvrook.restapi.suite.SuiteText;
import dev.ryvrook.restapi.suite.Waits;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The Coinage route group under /api/coinage. Reads go through the Coinage API, which reads memory, except the
 * ledger search and closed report days, which Coinage reads on its IO thread. The three writes move money through
 * {@code Ledger} with the initiator and plugin {@code RestAPI} and the reason {@code restapi:<key name>}, so the
 * ledger and the daily reports name the key, and only keys holding coinage.write reach them.
 */
public final class CoinageRoutes implements SiblingUnit {
    static final String GROUP = Sibling.COINAGE.group();
    static final Scope SCOPE = Sibling.COINAGE.scope();
    static final Scope WRITE = Scope.COINAGE_WRITE;
    static final String MOD = "Coinage";
    static final String PLUGIN = "RestAPI";

    private volatile CoinageApi current;
    private Consumer<CoinageApi> enableHook;
    private Runnable disableHook;
    private final Replays replays = new Replays(System::currentTimeMillis);

    @Override
    public List<Route> bind(RestApi api) {
        enableHook = c -> current = c;
        disableHook = () -> current = null;
        CoinageApi.onEnable(enableHook);
        CoinageApi.onDisable(disableHook);
        if (current == null) {
            CoinageApi.removeHooks(enableHook, disableHook);
            throw new IllegalStateException("Coinage has not handed out its API yet");
        }
        boolean writes = SuiteSettings.COINAGE_WRITES.get() && writeScopeAccepted();
        return routes(() -> current, Limits.live(), writes, CoinageWrites::live, replays, api.audit());
    }

    @Override
    public void unbind() {
        if (enableHook != null) {
            CoinageApi.removeHooks(enableHook, disableHook);
        }
        current = null;
        replays.clear();
    }

    /**
     * True when the sibling registry accepts the coinage.write scope in this group. RestAPI 0.1.0 checks every
     * suite route against the read scope alone, the registry that knows a write scope per sibling has
     * {@code Sibling.writeScope()}.
     */
    static boolean writeScopeAccepted() {
        try {
            return Sibling.class.getMethod("writeScope").invoke(Sibling.COINAGE) == WRITE;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    static List<Route> routes(Supplier<CoinageApi> source, Supplier<Limits> limits, boolean writes,
                              Supplier<CoinageWrites> rules, Replays replays, AuditSink audit) {
        Supplier<CoinageApi> api = () -> {
            CoinageApi c = source.get();
            if (c == null) {
                throw new ApiException(503, "sibling_gone", SuiteText.get(SuiteText.SIBLING_GONE, "mod", MOD));
            }
            return c;
        };
        List<Route> out = new ArrayList<>();
        out.add(Route.of(GROUP, "GET", "/api/coinage", SCOPE, Access.READ,
                "Coinage version, the ledger totals and the currencies.",
                call -> summary(api.get())).doc(new RouteDoc(List.of(), null,
                "{\"version\":\"1.2.0\",\"apiVersion\":4,\"transactions\":5120,\"supply\":125000000,\"accounts\":44,\"players\":40,\"shared\":4,\"primary\":\"coins\",\"currencies\":[\"coins\"]}",
                List.of("sibling_gone"))));
        out.add(Route.of(GROUP, "GET", "/api/coinage/currencies", SCOPE, Access.READ,
                "Every currency with its symbol, decimals, starting and largest balance and exchange rate.",
                call -> currencies(api.get())));
        out.add(Route.of(GROUP, "GET", "/api/coinage/balances/{player}", SCOPE, Access.READ,
                "One player's balance in every currency, their rank and their net worth with its parts.",
                call -> balances(api.get(), call)).doc(new RouteDoc(
                List.of(new RouteDoc.Param("currency", "The currency for the net worth and rank, the primary one when missing")),
                null, "{\"uuid\":\"...\",\"name\":\"Steve\",\"balances\":{\"coins\":{\"minor\":2500,\"text\":\"25.00 Coins\"}},\"rank\":3,\"netWorth\":{\"total\":{\"minor\":4100,\"text\":\"41.00 Coins\"},\"parts\":{\"wallet\":2500,\"bank\":1600}}}",
                List.of("player_not_found", "unknown_currency"))));
        out.add(Route.of(GROUP, "GET", "/api/coinage/accounts", SCOPE, Access.READ,
                "Accounts, shared ones by default. type=player or type=all lists the others.",
                call -> accounts(api.get(), call, limits.get())).doc(new RouteDoc(
                List.of(new RouteDoc.Param("type", "shared, player or all"), new RouteDoc.Param("limit", "Rows"),
                        new RouteDoc.Param("offset", "Rows to skip")), null, null, List.of("bad_type"))));
        out.add(Route.of(GROUP, "GET", "/api/coinage/accounts/{account}", SCOPE, Access.READ,
                "One account by id, handle or player name, with its members, their permissions and the newest moves.",
                call -> account(api.get(), call, limits.get())).doc(new RouteDoc(
                List.of(new RouteDoc.Param("history", "How many of the newest moves to include, 0 to 100, default 10")),
                null, null, List.of("account_not_found"))));
        out.add(Route.of(GROUP, "GET", "/api/coinage/ledger", SCOPE, Access.READ,
                "Searches the ledger journal, newest first. Every filter is optional.",
                call -> ledger(api.get(), call, limits.get())).doc(new RouteDoc(
                List.of(new RouteDoc.Param("account", "An account id, handle or player"),
                        new RouteDoc.Param("player", "A player name or UUID, the same as account for a player"),
                        new RouteDoc.Param("currency", "A currency id"),
                        new RouteDoc.Param("type", "deposit, withdraw, transfer, set or reversal"),
                        new RouteDoc.Param("plugin", "The plugin that made the move, such as RestAPI or Coinage"),
                        new RouteDoc.Param("from", "Epoch milliseconds or an ISO instant"),
                        new RouteDoc.Param("to", "Epoch milliseconds or an ISO instant"),
                        new RouteDoc.Param("limit", "Rows")), null, null,
                List.of("account_not_found", "bad_type", "bad_time", "sibling_timeout"))));
        out.add(Route.of(GROUP, "GET", "/api/coinage/ledger/{id}", SCOPE, Access.READ,
                "One ledger move by its id.", call -> transaction(api.get(), call)).doc(new RouteDoc(
                List.of(), null, null, List.of("transaction_not_found"))));
        out.add(Route.of(GROUP, "GET", "/api/coinage/top", SCOPE, Access.READ,
                "The rich list of a currency, by balance or by=networth, rebuilt by Coinage on its own timer.",
                call -> top(api.get(), call, limits.get())).doc(new RouteDoc(
                List.of(new RouteDoc.Param("currency", "A currency id, the primary one when missing"),
                        new RouteDoc.Param("by", "balance or networth"), new RouteDoc.Param("limit", "Rows"),
                        new RouteDoc.Param("offset", "Rows to skip")), null, null, List.of("unknown_currency"))));
        out.add(Route.of(GROUP, "GET", "/api/coinage/reports/today", SCOPE, Access.READ,
                "Today's totals of a currency so far, supply, treasury, minted, burned and volume by source.",
                call -> today(api.get(), call)));
        out.add(Route.of(GROUP, "GET", "/api/coinage/reports/days", SCOPE, Access.READ,
                "Closed days of a currency between two dates, at most 366.",
                call -> days(api.get(), call)).doc(new RouteDoc(
                List.of(new RouteDoc.Param("from", "First day as 2026-10-01, 30 days ago when missing"),
                        new RouteDoc.Param("to", "Last day, today when missing"),
                        new RouteDoc.Param("currency", "A currency id")), null, null, List.of("bad_date"))));
        if (writes) {
            out.add(write(api, rules, replays, audit, "transfer",
                    "Moves money from one account to another through the Coinage ledger. No overdraft, no fee.",
                    "{\"from\":\"Steve\",\"to\":\"Alex\",\"amount\":\"12.50\",\"currency\":\"coins\",\"reason\":\"event prize\"}"));
            out.add(write(api, rules, replays, audit, "give",
                    "Deposits money into an account from nowhere, as /coinage give does.",
                    "{\"to\":\"Steve\",\"amount\":\"100\",\"reason\":\"vote reward\"}"));
            out.add(write(api, rules, replays, audit, "take",
                    "Withdraws money from an account. Refused when the balance is short.",
                    "{\"from\":\"Steve\",\"minor\":2500}"));
        }
        return out;
    }

    private static Route write(Supplier<CoinageApi> api, Supplier<CoinageWrites> rules, Replays replays,
                               AuditSink audit, String verb, String summary, String example) {
        return Route.of(GROUP, "POST", "/api/coinage/" + verb, WRITE, Access.DANGEROUS, summary,
                call -> {
                    CoinageWrites r = rules.get();
                    String idem = call.request().header(Replays.HEADER);
                    if (idem == null && r.requireKey()) {
                        throw new ApiException(428, "idempotency_key_required", SuiteText.get(SuiteText.NEED_IDEMPOTENCY));
                    }
                    return replays.run(call.keyName(), idem, r.keepMillis(),
                            () -> move(api.get(), call, verb, r, audit));
                }).doc(new RouteDoc(List.of(), example,
                "{\"ok\":true,\"transaction\":{\"id\":5121,\"type\":\"transfer\",\"amount\":{\"minor\":1250,\"text\":\"12.50 Coins\"},\"plugin\":\"RestAPI\",\"reason\":\"restapi:grafana:event prize\"},\"fromAfter\":1250,\"toAfter\":3750,\"message\":\"Paid 12.50 Coins.\",\"replayed\":false}",
                List.of("account_not_found", "unknown_currency", "bad_amount", "over_cap", "insufficient_funds",
                        "account_locked", "account_frozen", "max_balance", "same_account", "idempotency_key_required",
                        "bad_idempotency_key")));
    }

    private static Response summary(CoinageApi c) {
        JsonObject o = new JsonObject();
        o.addProperty("version", c.version());
        o.addProperty("apiVersion", CoinageApi.API_VERSION);
        Stats s = c.ledger().stats();
        if (s != null) {
            o.addProperty("transactions", s.transactions());
            o.addProperty("lastId", s.lastId());
            o.addProperty("supply", s.supply());
            o.addProperty("burned", s.burned());
            o.addProperty("accounts", s.accounts());
            o.addProperty("players", s.players());
            o.addProperty("shared", s.shared());
        }
        Currency primary = c.currencies().primary();
        o.addProperty("primary", primary == null ? null : primary.id());
        o.add("currencies", Json.strings(c.currencies().all().stream().map(Currency::id).toList()));
        return Response.ok(o);
    }

    private static Response currencies(CoinageApi c) {
        JsonArray a = new JsonArray();
        for (Currency cur : c.currencies().all()) {
            a.add(CoinageJson.currency(cur));
        }
        JsonObject o = new JsonObject();
        o.add("currencies", a);
        return Response.ok(o);
    }

    static Names names(CoinageApi c) {
        return new Names(MOD, Names::online, name -> c.accounts().uuidOf(name));
    }

    static Currency currency(CoinageApi c, String id) {
        Optional<Currency> cur = c.currencies().resolve(id);
        if (cur.isEmpty()) {
            throw ApiException.badRequest("unknown_currency", SuiteText.get(SuiteText.UNKNOWN_CURRENCY, "currency", id));
        }
        return cur.get();
    }

    private static Response balances(CoinageApi c, Call call) {
        String player = call.param("player");
        UUID uuid = names(c).resolve(player);
        Accounts accounts = c.accounts();
        Account a = accounts.get(uuid).filter(x -> x.type() == AccountType.PLAYER).orElseThrow(() ->
                ApiException.notFound("player_not_found", SuiteText.get(SuiteText.PLAYER_NOT_FOUND, "player", player, "mod", MOD)));
        Currency cur = currency(c, call.query("currency", null));
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid.toString());
        String name = accounts.nameOf(uuid).filter(n -> !n.isBlank()).orElse(a.name());
        o.add("name", Json.str(name == null || name.isBlank() ? null : name));
        o.addProperty("status", CoinageJson.lower(a.status()));
        o.add("balances", CoinageJson.balances(c.currencies(), a.balances()));
        o.addProperty("currency", cur.id());
        o.addProperty("rank", accounts.rank(uuid, cur.id()));
        NetWorth worth = c.reports() == null ? null : c.reports().netWorth(uuid, cur.id());
        if (worth != null) {
            JsonObject w = new JsonObject();
            w.add("total", CoinageJson.amount(c.currencies(), cur.id(), worth.total()));
            w.add("parts", Json.numbers(worth.parts()));
            o.add("netWorth", w);
        }
        return Response.ok(o);
    }

    private static Response accounts(CoinageApi c, Call call, Limits limits) {
        String type = call.query("type", "shared").toLowerCase(Locale.ROOT);
        List<Account> list = switch (type) {
            case "shared" -> c.accounts().shared();
            case "player", "players" -> c.accounts().players();
            case "all" -> c.accounts().all();
            default -> throw ApiException.badRequest("bad_type", SuiteText.get(SuiteText.BAD_TYPE, "types", "shared, player, all"));
        };
        int limit = limits.limit(call);
        int offset = limits.offset(call);
        JsonArray rows = new JsonArray();
        for (int i = offset; i < list.size() && i < offset + limit; i++) {
            rows.add(CoinageJson.account(c.currencies(), list.get(i), false));
        }
        return Response.ok(Json.page("accounts", rows, list.size(), limit, offset));
    }

    /** An account by id, handle or player name. 404 account_not_found. */
    static Account findAccount(CoinageApi c, String text) {
        Accounts accounts = c.accounts();
        UUID id = Json.uuid(text);
        Optional<Account> a = id != null ? accounts.get(id) : accounts.byHandle(text);
        if (a.isEmpty() && id == null) {
            Optional<UUID> player = Names.online(text).or(() -> accounts.uuidOf(text));
            a = player.flatMap(accounts::get);
        }
        return a.orElseThrow(() -> ApiException.notFound("account_not_found",
                SuiteText.get(SuiteText.ACCOUNT_NOT_FOUND, "account", text)));
    }

    private static Response account(CoinageApi c, Call call, Limits limits) {
        Account a = findAccount(c, call.param("account"));
        int history = 10;
        String raw = call.query("history", null);
        if (raw != null && !raw.isBlank()) {
            history = Math.max(0, Math.min(100, Json.integer(raw, "history")));
        }
        JsonObject o = CoinageJson.account(c.currencies(), a, true);
        JsonArray moves = new JsonArray();
        if (history > 0) {
            for (Transaction t : c.ledger().history(a.id(), history)) {
                moves.add(CoinageJson.transaction(c.currencies(), t));
            }
        }
        o.add("history", moves);
        return Response.ok(o);
    }

    private static Response ledger(CoinageApi c, Call call, Limits limits) {
        String account = call.query("account", null);
        String player = call.query("player", null);
        UUID id = null;
        if (account != null && !account.isBlank()) {
            id = findAccount(c, account).id();
        } else if (player != null && !player.isBlank()) {
            id = names(c).resolve(player);
        }
        String currency = call.query("currency", null);
        if (currency != null && !currency.isBlank()) {
            currency = currency(c, currency).id();
        } else {
            currency = null;
        }
        Transaction.Type type = null;
        String rawType = call.query("type", null);
        if (rawType != null && !rawType.isBlank()) {
            try {
                type = Transaction.Type.valueOf(rawType.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("bad_type", SuiteText.get(SuiteText.BAD_TYPE, "types",
                        String.join(", ", Arrays.stream(Transaction.Type.values()).map(CoinageJson::lower).toList())));
            }
        }
        String plugin = call.query("plugin", null);
        int limit = limits.limit(call);
        Query q = new Query(id, currency, Json.time(call.query("from", null), "from", 0L),
                Json.time(call.query("to", null), "to", Long.MAX_VALUE), type,
                plugin == null || plugin.isBlank() ? null : plugin, limit);
        List<Transaction> found = Waits.await(c.ledger().search(q), MOD, limits.waitMs());
        JsonArray rows = new JsonArray();
        for (int i = 0; i < found.size() && i < limit; i++) {
            rows.add(CoinageJson.transaction(c.currencies(), found.get(i)));
        }
        JsonObject o = new JsonObject();
        o.add("transactions", rows);
        return Response.ok(o);
    }

    private static Response transaction(CoinageApi c, Call call) {
        String raw = call.param("id");
        long id = Json.whole(raw, "id");
        Transaction t = c.ledger().find(id).orElseThrow(() -> ApiException.notFound("transaction_not_found",
                SuiteText.get(SuiteText.TX_NOT_FOUND, "id", raw)));
        return Response.ok(CoinageJson.transaction(c.currencies(), t));
    }

    private static Response top(CoinageApi c, Call call, Limits limits) {
        Currency cur = currency(c, call.query("currency", null));
        String by = call.query("by", "balance").toLowerCase(Locale.ROOT);
        int limit = limits.limit(call);
        int offset = limits.offset(call);
        List<Rank> ranks = switch (by) {
            case "balance" -> c.accounts().top(cur.id(), offset, limit);
            case "networth", "net_worth" -> c.reports().topNetWorth(cur.id(), offset, limit);
            default -> throw ApiException.badRequest("bad_type", SuiteText.get(SuiteText.BAD_TYPE, "types", "balance, networth"));
        };
        JsonArray rows = new JsonArray();
        for (Rank r : ranks) {
            JsonObject o = new JsonObject();
            o.addProperty("rank", r.rank());
            o.addProperty("uuid", r.id().toString());
            o.addProperty("name", r.name());
            o.add("value", CoinageJson.amount(c.currencies(), cur.id(), r.balance()));
            rows.add(o);
        }
        JsonObject o = new JsonObject();
        o.addProperty("currency", cur.id());
        o.addProperty("by", by.startsWith("net") ? "networth" : "balance");
        o.addProperty("limit", limit);
        o.addProperty("offset", offset);
        o.add("rows", rows);
        return Response.ok(o);
    }

    private static Response today(CoinageApi c, Call call) {
        Currency cur = currency(c, call.query("currency", null));
        return Response.ok(report(c.reports().today(cur.id())));
    }

    private static Response days(CoinageApi c, Call call) {
        Currency cur = currency(c, call.query("currency", null));
        LocalDate to = date(call.query("to", null), LocalDate.now());
        LocalDate from = date(call.query("from", null), to.minusDays(30));
        if (from.isAfter(to) || from.plusDays(366).isBefore(to)) {
            throw ApiException.badRequest("bad_date", SuiteText.get(SuiteText.BAD_RANGE));
        }
        JsonArray a = new JsonArray();
        for (DailyReport d : c.reports().days(cur.id(), from, to)) {
            a.add(report(d));
        }
        JsonObject o = new JsonObject();
        o.addProperty("currency", cur.id());
        o.add("days", a);
        return Response.ok(o);
    }

    private static JsonElement report(DailyReport d) {
        if (d == null) {
            return new JsonObject();
        }
        JsonElement e = Json.of(d);
        if (e.isJsonObject()) {
            e.getAsJsonObject().addProperty("day", d.day() == null ? null : d.day().toString());
        }
        return e;
    }

    private static LocalDate date(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("bad_date", SuiteText.get(SuiteText.BAD_DATE));
        }
    }

    /** The move itself. Runs once per idempotency key. */
    static Response move(CoinageApi c, Call call, String verb, CoinageWrites rules, AuditSink audit) {
        JsonObject body = call.jsonBody();
        Currency cur = currency(c, text(body, "currency"));
        long amount = amount(body, cur);
        if (rules.maxAmount() > 0 && amount > rules.maxAmount()) {
            throw ApiException.badRequest("over_cap", SuiteText.get(SuiteText.OVER_CAP,
                    "amount", cur.format(amount), "cap", cur.format(rules.maxAmount())));
        }
        String reason = reason(call.keyName(), text(body, "reason"));
        Request req = switch (verb) {
            case "transfer" -> Request.transfer(target(c, body, "from", false), target(c, body, "to", false), cur.id(), amount);
            case "give" -> Request.deposit(target(c, body, "to", true), cur.id(), amount);
            case "take" -> Request.withdraw(target(c, body, "from", true), cur.id(), amount);
            default -> throw new IllegalArgumentException(verb);
        };
        req = req.by(Initiator.plugin(PLUGIN)).plugin(PLUGIN).reason(reason).overdraft(false);
        Result r = c.ledger().apply(req);
        JsonObject o = new JsonObject();
        o.addProperty("ok", r.ok());
        o.addProperty("message", r.message());
        if (!r.ok()) {
            Failure f = r.failure() == null ? Failure.NONE : r.failure();
            o.addProperty("error", CoinageJson.lower(f));
            o.addProperty("replayed", false);
            return Response.json(status(f), o);
        }
        Transaction t = r.tx();
        o.add("transaction", t == null ? null : CoinageJson.transaction(c.currencies(), t));
        o.addProperty("fromAfter", r.fromAfter());
        o.addProperty("toAfter", r.toAfter());
        o.addProperty("replayed", false);
        if (t != null) {
            audit.record(new AuditEntry(Instant.now().toString(), "http", call.keyName(), call.client(),
                    "coinage " + verb, 200, "tx " + t.id() + " " + cur.format(amount)));
        }
        return Response.ok(o);
    }

    /** The account in field, or in player for give and take, which name one account only. */
    private static UUID target(CoinageApi c, JsonObject body, String field, boolean playerToo) {
        String text = text(body, field);
        if (text == null && playerToo) {
            text = text(body, "player");
        }
        if (text == null || text.isBlank()) {
            throw ApiException.badRequest("missing_" + field, SuiteText.get(SuiteText.NEED_TARGET, "field", field));
        }
        return findAccount(c, text.trim()).id();
    }

    private static String text(JsonObject body, String field) {
        JsonElement e = body.get(field);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e.isJsonPrimitive()) {
            return e.getAsString();
        }
        throw ApiException.badRequest("bad_" + field, SuiteText.get(SuiteText.NOT_TEXT, "field", field));
    }

    /** {@code minor} in whole minor units, or {@code amount} as text or a number in the currency's units. */
    static long amount(JsonObject body, Currency cur) {
        JsonElement minor = body.get("minor");
        long value;
        if (minor != null && !minor.isJsonNull()) {
            try {
                value = minor.getAsJsonPrimitive().isNumber() ? minor.getAsLong() : Long.parseLong(minor.getAsString().trim());
            } catch (RuntimeException e) {
                throw badAmount();
            }
        } else {
            JsonElement a = body.get("amount");
            if (a == null || a.isJsonNull() || !a.isJsonPrimitive()) {
                throw badAmount();
            }
            String text = a.getAsJsonPrimitive().isNumber() ? a.getAsBigDecimal().toPlainString() : a.getAsString();
            OptionalLong parsed = cur.parse(text);
            if (parsed.isEmpty()) {
                throw badAmount();
            }
            value = parsed.getAsLong();
        }
        if (value <= 0 || value > cur.maxBalance()) {
            throw badAmount();
        }
        return value;
    }

    private static ApiException badAmount() {
        return ApiException.badRequest("bad_amount", SuiteText.get(SuiteText.BAD_AMOUNT));
    }

    /** {@code restapi:<key>} or {@code restapi:<key>:<reason>}, the source before the first colon is restapi. */
    static String reason(String keyName, String given) {
        String base = "restapi:" + keyName;
        if (given == null || given.isBlank()) {
            return base;
        }
        StringBuilder b = new StringBuilder();
        given.strip().codePoints().filter(cp -> !Character.isISOControl(cp)).limit(120).forEach(b::appendCodePoint);
        return base + ":" + b;
    }

    static int status(Failure f) {
        return switch (f) {
            case ACCOUNT_NOT_FOUND -> 404;
            case UNKNOWN_CURRENCY, INVALID_AMOUNT, SAME_ACCOUNT -> 400;
            case NOT_PERMITTED -> 403;
            case STOPPING -> 503;
            default -> 409;
        };
    }
}
