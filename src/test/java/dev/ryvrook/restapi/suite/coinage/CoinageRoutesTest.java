package dev.ryvrook.restapi.suite.coinage;

import com.google.gson.JsonObject;
import dev.ryvrook.coinage.api.Account;
import dev.ryvrook.coinage.api.AccountStatus;
import dev.ryvrook.coinage.api.AccountType;
import dev.ryvrook.coinage.api.Accounts;
import dev.ryvrook.coinage.api.CoinageApi;
import dev.ryvrook.coinage.api.Currencies;
import dev.ryvrook.coinage.api.Currency;
import dev.ryvrook.coinage.api.EconomyListener;
import dev.ryvrook.coinage.api.Failure;
import dev.ryvrook.coinage.api.Ledger;
import dev.ryvrook.coinage.api.Mail;
import dev.ryvrook.coinage.api.NetWorth;
import dev.ryvrook.coinage.api.NetWorthParts;
import dev.ryvrook.coinage.api.Query;
import dev.ryvrook.coinage.api.Rank;
import dev.ryvrook.coinage.api.Reports;
import dev.ryvrook.coinage.api.Request;
import dev.ryvrook.coinage.api.Result;
import dev.ryvrook.coinage.api.Stats;
import dev.ryvrook.coinage.api.Subscription;
import dev.ryvrook.coinage.api.Transaction;
import dev.ryvrook.restapi.audit.AuditEntry;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.suite.Calls;
import dev.ryvrook.restapi.suite.Limits;
import dev.ryvrook.restapi.suite.Replays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CoinageRoutesTest {
    static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final UUID GUILD = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    static final Currency COINS = Currency.coins();

    static Account account(UUID id, String handle, String name, AccountType type, long balance) {
        return Calls.stand(Account.class, Map.ofEntries(
                Map.entry("id", a -> id), Map.entry("handle", a -> handle), Map.entry("name", a -> name),
                Map.entry("type", a -> type), Map.entry("owner", a -> type == AccountType.SHARED ? STEVE : null),
                Map.entry("status", a -> AccountStatus.ACTIVE), Map.entry("balance", a -> balance),
                Map.entry("balances", a -> Map.of(COINS.id(), balance)),
                Map.entry("members", a -> type == AccountType.SHARED ? Set.of(ALEX) : Set.of()),
                Map.entry("permissions", a -> Set.of()), Map.entry("createdAt", a -> 5L), Map.entry("seq", a -> 1L)));
    }

    /** Coinage as a consumer sees it, three accounts and a ledger that records what it was asked. */
    static final class FakeCoinage extends CoinageApi {
        final Map<UUID, Account> byId = Map.of(
                STEVE, account(STEVE, "p:steve", "Steve", AccountType.PLAYER, 2500),
                ALEX, account(ALEX, "p:alex", "Alex", AccountType.PLAYER, 100),
                GUILD, account(GUILD, "guild:north", "North Watch", AccountType.SHARED, 9000));
        final List<Request> moves = new ArrayList<>();
        final AtomicReference<Query> searched = new AtomicReference<>();
        final AtomicLong nextId = new AtomicLong(100);
        Failure refuse = null;

        final Currencies currencies = Calls.stand(Currencies.class, Map.of(
                "primary", a -> COINS,
                "get", a -> COINS.id().equals(a[0]) ? Optional.of(COINS) : Optional.empty(),
                "all", a -> List.of(COINS)));
        final Accounts accounts = Calls.stand(Accounts.class, Map.ofEntries(
                Map.entry("get", a -> Optional.ofNullable(byId.get((UUID) a[0]))),
                Map.entry("byHandle", a -> byId.values().stream().filter(x -> x.handle().equals(a[0])).findFirst()),
                Map.entry("uuidOf", a -> byId.values().stream().filter(x -> x.type() == AccountType.PLAYER
                        && x.name().equalsIgnoreCase((String) a[0])).map(Account::id).findFirst()),
                Map.entry("nameOf", a -> Optional.ofNullable(byId.get((UUID) a[0])).map(Account::name)),
                Map.entry("shared", a -> List.of(byId.get(GUILD))),
                Map.entry("players", a -> List.of(byId.get(STEVE), byId.get(ALEX))),
                Map.entry("all", a -> List.of(byId.get(STEVE), byId.get(ALEX), byId.get(GUILD))),
                Map.entry("rank", a -> 2),
                Map.entry("top", a -> List.of(new Rank(1, STEVE, "Steve", 2500), new Rank(2, ALEX, "Alex", 100)))));
        final Ledger ledger = Calls.stand(Ledger.class, Map.of(
                "apply", a -> apply((Request) a[0]),
                "history", a -> List.of(tx(1, STEVE, ALEX, 50)),
                "search", a -> {
                    searched.set((Query) a[0]);
                    return CompletableFuture.completedFuture(List.of(tx(2, null, STEVE, 75)));
                },
                "find", a -> (Long) a[0] == 2L ? Optional.of(tx(2, null, STEVE, 75)) : Optional.empty(),
                "stats", a -> new Stats(10, 9, 11600, 0, 3, 2, 1)));
        final Reports reports = Calls.stand(Reports.class, Map.of(
                "netWorth", a -> new NetWorth((UUID) a[0], (String) a[1], 4100, Map.of("wallet", 2500L, "bank", 1600L)),
                "topNetWorth", a -> List.of(new Rank(1, STEVE, "Steve", 4100))));

        Result apply(Request r) {
            moves.add(r);
            if (refuse != null) {
                return Result.fail(refuse, "You do not have enough Coins.");
            }
            Transaction t = new Transaction(nextId.incrementAndGet(), 1L, r.kind(), r.currency(), r.from(), r.to(), r.amount(),
                    0, null, 0, 0, r.initiator(), r.plugin(), r.reason(), 0);
            return Result.ok("Done.", 1000, 2000, t);
        }

        static Transaction tx(long id, UUID from, UUID to, long amount) {
            return new Transaction(id, 7L, Transaction.Type.TRANSFER, "coins", from, to, amount, 0, null, 0, 0,
                    dev.ryvrook.coinage.api.Initiator.server(), "Coinage", "pay", 0);
        }

        @Override public Currencies currencies() { return currencies; }
        @Override public Accounts accounts() { return accounts; }
        @Override public Ledger ledger() { return ledger; }
        @Override public Mail mail() { throw new UnsupportedOperationException(); }
        @Override public Reports reports() { return reports; }
        @Override public <T> Optional<T> stats(Class<T> type) { return Optional.empty(); }
        @Override public <T> Optional<T> service(Class<T> type) { return Optional.empty(); }
        @Override public com.google.gson.JsonObject config() { return new com.google.gson.JsonObject(); }
        @Override public NetWorthParts netWorth() { throw new UnsupportedOperationException(); }
        @Override public Subscription subscribe(String owner, EconomyListener l) { throw new UnsupportedOperationException(); }
        @Override public List<Subscription> subscriptions() { return List.of(); }
        @Override public String version() { return "1.2.0"; }
    }

    FakeCoinage coinage;
    List<AuditEntry> audit;
    Calls calls;
    CoinageWrites rules;

    @BeforeEach
    void setUp() {
        coinage = new FakeCoinage();
        audit = new ArrayList<>();
        rules = CoinageWrites.DEFAULTS;
        calls = new Calls(CoinageRoutes.routes(() -> coinage, () -> Limits.DEFAULTS, true, () -> rules,
                new Replays(System::currentTimeMillis), audit::add));
    }

    @Test
    void readsNeedCoinageAndWritesNeedCoinageWrite() {
        for (Route r : CoinageRoutes.routes(() -> coinage, () -> Limits.DEFAULTS, true, () -> rules, new Replays(() -> 0), e -> { })) {
            assertTrue(r.pattern().equals("/api/coinage") || r.pattern().startsWith("/api/coinage/"));
            if (r.method().equals("POST")) {
                assertEquals(Scope.COINAGE_WRITE, r.scope(), r.pattern());
                assertEquals(Access.DANGEROUS, r.access());
            } else {
                assertEquals(Scope.COINAGE, r.scope(), r.pattern());
                assertEquals(Access.READ, r.access());
            }
        }
    }

    @Test
    void withoutWritesThereAreNoPostRoutes() {
        Calls reads = new Calls(CoinageRoutes.routes(() -> coinage, () -> Limits.DEFAULTS, false, () -> rules, new Replays(() -> 0), e -> { }));
        assertFalse(reads.has("POST", "/api/coinage/transfer"));
        assertTrue(reads.has("GET", "/api/coinage/top"));
    }

    @Test
    void theRegistryOfThisBuildDecidesWhetherWritesMount() {
        boolean known = java.util.Arrays.stream(dev.ryvrook.restapi.siblings.Sibling.class.getMethods())
                .anyMatch(m -> m.getName().equals("writeScope"));
        assertEquals(known, CoinageRoutes.writeScopeAccepted());
    }

    @Test
    void summaryAndCurrencies() {
        JsonObject o = Calls.json(calls.get("/api/coinage"));
        assertEquals("1.2.0", o.get("version").getAsString());
        assertEquals(11600, o.get("supply").getAsLong());
        assertEquals("coins", o.get("primary").getAsString());
        JsonObject c = Calls.json(calls.get("/api/coinage/currencies")).getAsJsonArray("currencies").get(0).getAsJsonObject();
        assertEquals(COINS.decimals(), c.get("decimals").getAsInt());
    }

    @Test
    void balancesByNameWithNetWorthAndRank() {
        JsonObject o = Calls.json(calls.get("/api/coinage/balances/steve"));
        assertEquals(STEVE.toString(), o.get("uuid").getAsString());
        assertEquals(2500, o.getAsJsonObject("balances").getAsJsonObject("coins").get("minor").getAsLong());
        assertEquals(COINS.format(2500), o.getAsJsonObject("balances").getAsJsonObject("coins").get("text").getAsString());
        assertEquals(2, o.get("rank").getAsInt());
        assertEquals(4100, o.getAsJsonObject("netWorth").getAsJsonObject("total").get("minor").getAsLong());
        assertEquals(404, calls.get("/api/coinage/balances/Nobody").status());
        assertEquals(400, calls.get("/api/coinage/balances/steve?currency=gems").status());
    }

    @Test
    void accountsPageAndDetail() {
        JsonObject list = Calls.json(calls.get("/api/coinage/accounts"));
        assertEquals(1, list.get("total").getAsInt());
        assertEquals(2, Calls.json(calls.get("/api/coinage/accounts?type=player")).get("total").getAsInt());
        assertEquals(400, calls.get("/api/coinage/accounts?type=bank").status());
        JsonObject guild = Calls.json(calls.get("/api/coinage/accounts/guild:north"));
        assertEquals("shared", guild.get("type").getAsString());
        assertEquals(ALEX.toString(), guild.getAsJsonArray("members").get(0).getAsJsonObject().get("uuid").getAsString());
        assertEquals(1, guild.getAsJsonArray("history").size());
        assertEquals("Steve", Calls.json(calls.get("/api/coinage/accounts/Steve")).get("name").getAsString());
        assertEquals(404, calls.get("/api/coinage/accounts/nothing").status());
    }

    @Test
    void theLedgerSearchCarriesTheFilters() {
        JsonObject o = Calls.json(calls.get("/api/coinage/ledger?player=Steve&type=deposit&plugin=RestAPI&currency=coins&from=10&limit=5"));
        Query q = coinage.searched.get();
        assertEquals(STEVE, q.account());
        assertEquals(Transaction.Type.DEPOSIT, q.type());
        assertEquals("RestAPI", q.plugin());
        assertEquals(10L, q.from());
        assertEquals(5, q.limit());
        assertEquals(1, o.getAsJsonArray("transactions").size());
        assertEquals(400, calls.get("/api/coinage/ledger?type=gift").status());
        assertEquals(75, Calls.json(calls.get("/api/coinage/ledger/2")).getAsJsonObject("amount").get("minor").getAsLong());
        assertEquals(404, calls.get("/api/coinage/ledger/3").status());
    }

    @Test
    void theRichListByBalanceOrNetWorth() {
        JsonObject o = Calls.json(calls.get("/api/coinage/top?limit=2"));
        assertEquals("balance", o.get("by").getAsString());
        assertEquals(2, o.getAsJsonArray("rows").size());
        JsonObject w = Calls.json(calls.get("/api/coinage/top?by=networth"));
        assertEquals(4100, w.getAsJsonArray("rows").get(0).getAsJsonObject().getAsJsonObject("value").get("minor").getAsLong());
    }

    @Test
    void aTransferNamesRestApiAndTheKey() {
        Response r = calls.post("/api/coinage/transfer",
                "{\"from\":\"Steve\",\"to\":\"guild:north\",\"amount\":\"12.50\",\"reason\":\"event prize\"}");
        assertEquals(200, r.status(), r.bodyText());
        Request req = coinage.moves.get(0);
        assertEquals(Transaction.Type.TRANSFER, req.kind());
        assertEquals(STEVE, req.from());
        assertEquals(GUILD, req.to());
        assertEquals(COINS.parse("12.50").getAsLong(), req.amount());
        assertEquals("RestAPI", req.plugin());
        assertEquals("RestAPI", req.initiator().name());
        assertEquals("restapi:grafana:event prize", req.reason());
        assertFalse(req.allowOverdraft());
        JsonObject o = Calls.json(r);
        assertTrue(o.get("ok").getAsBoolean());
        assertFalse(o.get("replayed").getAsBoolean());
        assertEquals(1, audit.size());
        assertTrue(audit.get(0).detail().startsWith("tx 101"));
        assertEquals("grafana", audit.get(0).key());
    }

    @Test
    void giveAndTakeTakeOneAccount() {
        assertEquals(200, calls.post("/api/coinage/give", "{\"player\":\"Alex\",\"minor\":500}").status());
        assertEquals(Transaction.Type.DEPOSIT, coinage.moves.get(0).kind());
        assertEquals(ALEX, coinage.moves.get(0).to());
        assertEquals("restapi:grafana", coinage.moves.get(0).reason());
        assertEquals(200, calls.post("/api/coinage/take", "{\"from\":\"" + STEVE + "\",\"amount\":3}").status());
        assertEquals(Transaction.Type.WITHDRAW, coinage.moves.get(1).kind());
        assertEquals(STEVE, coinage.moves.get(1).from());
        Response missing = calls.post("/api/coinage/transfer", "{\"to\":\"Alex\",\"minor\":5}");
        assertEquals(400, missing.status());
        assertEquals("missing_from", Calls.json(missing).get("error").getAsString());
    }

    @Test
    void aRetryWithTheSameIdempotencyKeyMovesOnce() {
        String body = "{\"from\":\"Steve\",\"to\":\"Alex\",\"minor\":100}";
        Response first = calls.post("/api/coinage/transfer", body, "Idempotency-Key", "order-7");
        Response second = calls.post("/api/coinage/transfer", body, "Idempotency-Key", "order-7");
        assertEquals(1, coinage.moves.size());
        assertEquals(200, second.status());
        assertTrue(Calls.json(second).get("replayed").getAsBoolean());
        assertEquals(Calls.json(first).getAsJsonObject("transaction").get("id"), Calls.json(second).getAsJsonObject("transaction").get("id"));
        calls.post("/api/coinage/transfer", body, "Idempotency-Key", "order-8");
        assertEquals(2, coinage.moves.size());
    }

    @Test
    void amountsAreCheckedBeforeTheLedger() {
        assertEquals("bad_amount", Calls.json(calls.post("/api/coinage/give", "{\"to\":\"Alex\",\"amount\":\"-4\"}")).get("error").getAsString());
        assertEquals("bad_amount", Calls.json(calls.post("/api/coinage/give", "{\"to\":\"Alex\",\"minor\":0}")).get("error").getAsString());
        assertEquals("bad_amount", Calls.json(calls.post("/api/coinage/give", "{\"to\":\"Alex\"}")).get("error").getAsString());
        rules = new CoinageWrites(1000, false, 60_000);
        Response over = calls.post("/api/coinage/give", "{\"to\":\"Alex\",\"minor\":1001}");
        assertEquals(400, over.status());
        assertEquals("over_cap", Calls.json(over).get("error").getAsString());
        assertTrue(coinage.moves.isEmpty());
        assertEquals("unknown_currency", Calls.json(calls.post("/api/coinage/give", "{\"to\":\"Alex\",\"minor\":5,\"currency\":\"gems\"}")).get("error").getAsString());
        assertEquals("account_not_found", Calls.json(calls.post("/api/coinage/give", "{\"to\":\"Nobody\",\"minor\":5}")).get("error").getAsString());
    }

    @Test
    void aRefusedMoveSaysWhy() {
        coinage.refuse = Failure.INSUFFICIENT_FUNDS;
        Response r = calls.post("/api/coinage/take", "{\"from\":\"Alex\",\"minor\":5000}");
        assertEquals(409, r.status());
        JsonObject o = Calls.json(r);
        assertEquals("insufficient_funds", o.get("error").getAsString());
        assertFalse(o.get("ok").getAsBoolean());
        assertTrue(audit.isEmpty());
    }

    @Test
    void anIdempotencyKeyCanBeRequired() {
        rules = new CoinageWrites(0, true, 60_000);
        Response r = calls.post("/api/coinage/give", "{\"to\":\"Alex\",\"minor\":5}");
        assertEquals(428, r.status());
        assertEquals(200, calls.post("/api/coinage/give", "{\"to\":\"Alex\",\"minor\":5}", "Idempotency-Key", "x1").status());
    }

    @Test
    void reasonsDropControlCharactersAndStayShort() {
        assertEquals("restapi:k", CoinageRoutes.reason("k", "  "));
        assertEquals("restapi:k:ab", CoinageRoutes.reason("k", "a\nb"));
        assertEquals(10 + 120, CoinageRoutes.reason("k", "x".repeat(500)).length());
    }

    @Test
    void failuresMapToStatuses() {
        assertEquals(404, CoinageRoutes.status(Failure.ACCOUNT_NOT_FOUND));
        assertEquals(400, CoinageRoutes.status(Failure.SAME_ACCOUNT));
        assertEquals(403, CoinageRoutes.status(Failure.NOT_PERMITTED));
        assertEquals(503, CoinageRoutes.status(Failure.STOPPING));
        assertEquals(409, CoinageRoutes.status(Failure.ACCOUNT_FROZEN));
    }
}
