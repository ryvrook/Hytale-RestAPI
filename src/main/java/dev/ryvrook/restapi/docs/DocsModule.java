package dev.ryvrook.restapi.docs;

import dev.ryvrook.restapi.RestApi;
import dev.ryvrook.restapi.RestModule;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.siblings.Siblings;

import java.util.List;

/**
 * The docs group, GET /openapi.json and the page at /docs. The document is written from the routes mounted at the
 * time of the request, so a suite group shows up while its mod is bound and leaves with it.
 */
public final class DocsModule implements RestModule {
    private volatile DocsRoutes current;
    private Siblings listening;

    public DocsModule() {
        DocsSettings.PUBLIC.key();
        DocsText.INFO.length();
    }

    @Override
    public String group() {
        return DocsRoutes.GROUP;
    }

    @Override
    public List<Route> routes(RestApi api) throws Exception {
        DocsRoutes d = new DocsRoutes(api.router()::routes, api.version(), DocsModule.class.getClassLoader());
        current = d;
        return d.routes();
    }

    @Override
    public void start(RestApi api) {
        Siblings s = api.siblings();
        // Siblings keeps its listeners for the life of the plugin, so listen once and reach the newest routes.
        if (s != null && s != listening) {
            s.listen(p -> {
                DocsRoutes d = current;
                if (d != null) {
                    d.invalidate();
                }
            });
            listening = s;
        }
    }

    @Override
    public void stop() {
        DocsRoutes d = current;
        if (d != null) {
            d.invalidate();
        }
    }
}
