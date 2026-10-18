package dev.ryvrook.restapi.dashboard;

import dev.ryvrook.restapi.text.Messages;

/**
 * The sentences of the dashboard routes and of /restapi dashboard. Admins reword them in messages.json like every
 * other sentence. Error codes stay fixed for scripts.
 */
public final class DashboardText {
    private DashboardText() {
    }

    public static final String ROUTE_PAGE = Messages.define("dashboard.route.page",
            "The admin dashboard page. /dashboard sends the browser on to /dashboard/.");
    public static final String ROUTE_FILE = Messages.define("dashboard.route.file",
            "A script, style sheet, image or font of the dashboard page.");
    public static final String ROUTE_CAPABILITIES = Messages.define("dashboard.route.capabilities",
            "What the page may do, read once when it opens. Without a session it says only that a login is needed.");
    public static final String ROUTE_LOGIN = Messages.define("dashboard.route.login",
            "Opens a dashboard session from a login link of /restapi dashboard login, or shows the login page without one.");
    public static final String ROUTE_LOGOUT = Messages.define("dashboard.route.logout", "Ends this browser's dashboard session.");
    public static final String ROUTE_ASSET_INDEX = Messages.define("dashboard.route.assetIndex",
            "The names of the item, creature and map marker icons Assets.zip holds.");
    public static final String ROUTE_ASSET = Messages.define("dashboard.route.asset",
            "One game icon from Assets.zip, kind items, markers or models.");
    public static final String ROUTE_AVATAR = Messages.define("dashboard.route.avatar",
            "A player's character for the busts, passed through from Chronicle Stats.");
    public static final String ROUTE_CHRONICLE_ASSET = Messages.define("dashboard.route.chronicleAsset",
            "A character file for the busts, passed through from Chronicle Stats.");

    public static final String NO_FILE = Messages.define("dashboard.error.noFile", "The dashboard page has no file named {file}.");
    public static final String NO_ASSETS = Messages.define("dashboard.error.noAssets",
            "No Assets.zip was found. Set dashboard.assets to its path and run /restapi reload.");
    public static final String NO_ICON = Messages.define("dashboard.error.noIcon", "The game has no {kind} icon called {name}.");
    public static final String ASSET_SHAPE = Messages.define("dashboard.error.assetShape",
            "Use /dashboard/assets/items, markers or models followed by a name of letters, digits, underscores and dashes and .png.");
    public static final String ASSET_BROKEN = Messages.define("dashboard.error.assetBroken", "That icon could not be read from Assets.zip.");
    public static final String NO_CHRONICLE = Messages.define("dashboard.error.noChronicle",
            "Character pictures need Chronicle Stats bound with its web server on, and dashboard.chronicle on.");
    public static final String CHRONICLE_DOWN = Messages.define("dashboard.error.chronicleDown",
            "Chronicle Stats did not answer on its web port.");
    public static final String CHRONICLE_SHAPE = Messages.define("dashboard.error.chronicleShape",
            "Only api/avatar and api/asset are passed to Chronicle Stats.");

    public static final String COMMAND = Messages.define("command.dashboard", "The admin dashboard RestAPI serves.");
    public static final String COMMAND_ON = Messages.define("command.dashboard.on", "Serve the dashboard and keep it on after a restart.");
    public static final String COMMAND_OFF = Messages.define("command.dashboard.off", "Stop serving the dashboard and end every session.");
    public static final String COMMAND_STATUS = Messages.define("command.dashboard.status",
            "Show the dashboard address, writes, sessions, game icons and Chronicle Stats.");
    public static final String COMMAND_LOGIN = Messages.define("command.dashboard.login", "Get a login link for the dashboard, shown only to you.");
    public static final String COMMAND_LOGOUT = Messages.define("command.dashboard.logout", "End every dashboard session.");
    public static final String COMMAND_WRITES = Messages.define("command.dashboard.writes", "Let dashboard sessions change the server, or not.");
    public static final String COMMAND_WRITES_ON = Messages.define("command.dashboard.writes.on", "Let dashboard sessions change the server.");
    public static final String COMMAND_WRITES_OFF = Messages.define("command.dashboard.writes.off", "Make dashboard sessions read only.");

    public static final String ON = Messages.define("dashboard.on",
            "The dashboard is on at {url}. Run /restapi dashboard login for a link that opens it.");
    public static final String ALREADY_ON = Messages.define("dashboard.alreadyOn", "The dashboard was already on at {url}.");
    public static final String OFF = Messages.define("dashboard.off", "The dashboard is off, its routes are gone and {sessions} ended.");
    public static final String ALREADY_OFF = Messages.define("dashboard.alreadyOff", "The dashboard was already off.");
    public static final String HTTP_DOWN = Messages.define("dashboard.httpDown",
            "The HTTP API is not running, so nobody can open the page until it is.");
    public static final String SAVE_FAILED = Messages.define("dashboard.saveFailed",
            "RestAPI.json could not be written, {reason}. Nothing changed.");
    public static final String NOT_MOUNTED = Messages.define("dashboard.notMounted",
            "The dashboard routes could not be mounted, the server log has the cause.");
    public static final String LINK = Messages.define("dashboard.link",
            "This link logs you in to the dashboard. It works once within {minutes} minutes, do not share it.");
    public static final String LOGIN_OFF = Messages.define("dashboard.loginOff",
            "The dashboard is off. Turn it on with /restapi dashboard on first.");
    public static final String LOGGED_OUT = Messages.define("dashboard.loggedOut", "Ended {sessions}. Login links not used yet stop working too.");
    public static final String WRITES_ON = Messages.define("dashboard.writesOn",
            "Dashboard sessions can change the server now. Every change goes to the audit log.");
    public static final String WRITES_OFF = Messages.define("dashboard.writesOff", "Dashboard sessions can only read now.");
    public static final String WRITES_SAME = Messages.define("dashboard.writesSame", "Writes were already {state}.");

    public static final String STATUS_ON = Messages.define("status.dashboard.on",
            "Dashboard on at {url}, writes {writes}, {sessions} open.");
    public static final String STATUS_OFF = Messages.define("status.dashboard.off",
            "Dashboard off, /restapi dashboard on serves it at {url}.");
    public static final String STATUS_ASSETS = Messages.define("status.dashboard.assets", "Game icons from {file}, {count} icons.");
    public static final String STATUS_NO_ASSETS = Messages.define("status.dashboard.noAssets",
            "No Assets.zip found, the page shows names without game icons. Set dashboard.assets to its path.");
    public static final String STATUS_CHRONICLE = Messages.define("status.dashboard.chronicle",
            "Character pictures pass through from Chronicle Stats at {url}.");
    public static final String STATUS_NO_CHRONICLE = Messages.define("status.dashboard.noChronicle",
            "No character pictures, {reason}.");
    public static final String STATUS_PAGE = Messages.define("status.dashboard.page",
            "Page files of dashboard {version} ({commit}), {files} files, {kb} KB.");

    public static final String REASON_SETTING = Messages.define("dashboard.reason.setting", "dashboard.chronicle is off");
    public static final String REASON_UNBOUND = Messages.define("dashboard.reason.unbound", "Chronicle Stats is not bound");
    public static final String REASON_WEB = Messages.define("dashboard.reason.web",
            "its web server is off or chronicle.json could not be read");
}
