package dev.ryvrook.restapi.docs;

import dev.ryvrook.restapi.config.Setting;
import dev.ryvrook.restapi.config.Settings;

/** The keys under docs in RestAPI.json. */
public final class DocsSettings {
    private DocsSettings() {
    }

    public static final Setting<Boolean> PUBLIC = Settings.bool("docs.public", true,
            "Answer GET /openapi.json without a key. Off asks for a key with the read scope, and the docs page asks for that key before it loads the document.");
    public static final Setting<Boolean> PAGE = Settings.bool("docs.page", true,
            "Serve the docs page at /docs. Off leaves /openapi.json for tools and removes the page and its two files.");
    public static final Setting<Boolean> TRY_IT = Settings.bool("docs.tryIt", true,
            "Show a send button under each route on the docs page. The key you paste stays in the page's memory and is gone when the tab closes.");
    public static final Setting<String> TITLE = Settings.text("docs.title", "RestAPI",
            "Title of the OpenAPI document and heading of the docs page, such as the server's name.");
    public static final Setting<String> SERVER_URL = Settings.text("docs.serverUrl", "",
            "URL the document lists under servers, such as https://play.example.com/restapi when a reverse proxy serves the API there. Empty uses http.basePath.");
}
