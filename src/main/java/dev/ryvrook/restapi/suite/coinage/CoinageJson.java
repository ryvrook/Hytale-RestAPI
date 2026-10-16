package dev.ryvrook.restapi.suite.coinage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ryvrook.coinage.api.Account;
import dev.ryvrook.coinage.api.Currencies;
import dev.ryvrook.coinage.api.Currency;
import dev.ryvrook.coinage.api.Initiator;
import dev.ryvrook.coinage.api.Transaction;
import dev.ryvrook.restapi.suite.Json;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Coinage records as JSON. Amounts are whole minor units with the formatted text next to them. */
final class CoinageJson {
    private CoinageJson() {
    }

    static JsonObject currency(Currency c) {
        JsonObject o = new JsonObject();
        o.addProperty("id", c.id());
        o.addProperty("singular", c.singular());
        o.addProperty("plural", c.plural());
        o.addProperty("symbol", c.symbol());
        o.addProperty("symbolAfter", c.symbolAfter());
        o.addProperty("decimals", c.decimals());
        o.addProperty("primary", c.primary());
        o.addProperty("payable", c.payable());
        o.addProperty("startingBalance", c.startingBalance());
        o.addProperty("maxBalance", c.maxBalance());
        o.addProperty("rate", c.rate());
        return o;
    }

    /** One amount as {minor, text}. */
    static JsonObject amount(Currencies currencies, String currency, long minor) {
        JsonObject o = new JsonObject();
        o.addProperty("minor", minor);
        Currency c = currency == null ? null : currencies.get(currency).orElse(null);
        o.addProperty("text", c == null ? Long.toString(minor) : c.format(minor));
        return o;
    }

    static JsonObject balances(Currencies currencies, Map<String, Long> balances) {
        JsonObject o = new JsonObject();
        new TreeMap<>(balances).forEach((cur, minor) -> o.add(cur, amount(currencies, cur, minor)));
        return o;
    }

    static JsonObject account(Currencies currencies, Account a, boolean members) {
        JsonObject o = new JsonObject();
        o.addProperty("id", a.id().toString());
        o.addProperty("handle", a.handle());
        o.addProperty("name", a.name());
        o.addProperty("type", lower(a.type()));
        o.add("owner", Json.str(a.owner()));
        o.addProperty("status", lower(a.status()));
        o.add("balances", balances(currencies, a.balances()));
        o.addProperty("createdAt", a.createdAt());
        if (members) {
            JsonArray m = new JsonArray();
            for (UUID u : a.members()) {
                JsonObject x = new JsonObject();
                x.addProperty("uuid", u.toString());
                x.add("permissions", Json.strings(a.permissions(u).stream().map(CoinageJson::lower).sorted().toList()));
                m.add(x);
            }
            o.add("members", m);
        } else {
            o.addProperty("members", a.members().size());
        }
        return o;
    }

    static JsonObject transaction(Currencies currencies, Transaction t) {
        JsonObject o = new JsonObject();
        o.addProperty("id", t.id());
        o.addProperty("at", t.at());
        o.addProperty("type", lower(t.type()));
        o.addProperty("currency", t.currency());
        o.add("from", Json.str(t.from()));
        o.add("to", Json.str(t.to()));
        o.add("amount", amount(currencies, t.currency(), t.amount()));
        o.addProperty("fee", t.fee());
        o.add("feeTo", Json.str(t.feeTo()));
        o.addProperty("fromAfter", t.fromAfter());
        o.addProperty("toAfter", t.toAfter());
        o.add("initiator", initiator(t.initiator()));
        o.addProperty("plugin", t.plugin());
        o.addProperty("reason", t.reason());
        o.addProperty("reverses", t.reverses());
        return o;
    }

    static JsonObject initiator(Initiator i) {
        JsonObject o = new JsonObject();
        if (i == null) {
            return o;
        }
        o.addProperty("type", lower(i.type()));
        o.add("player", Json.str(i.player()));
        o.addProperty("name", i.name());
        return o;
    }

    static String lower(Enum<?> e) {
        return e == null ? null : e.name().toLowerCase(Locale.ROOT);
    }
}
