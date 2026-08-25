package org.telegram.owpengram;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import android.text.TextUtils;

import android.graphics.Bitmap;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class OwpengramServers {

    private static final String PREFS_NAME = "owpengram_servers";
    private static final String KEY_CUSTOM_SERVERS = "custom_servers";
    private static final String KEY_ACCOUNT_SERVER = "account_server_";

    public static final String ID_OWPENGRAM = "owpengram";
    public static final String ID_TELEGRAM  = "telegram";

    /** Public repository of the OwpenGram server, opened from the settings entry. */
    public static final String SERVER_REPO_URL = "https://github.com/owpengram/owpengram-server";

    private static final String DEFAULT_HOST = "152.89.254.50";
    private static final int    DEFAULT_PORT = 2398;

    // OwpenGram's own production server RSA key.
    static final String OWPENGRAM_RSA_KEY =
        "-----BEGIN RSA PUBLIC KEY-----\n" +
        "MIIBCgKCAQEAxUi0fsjiop7kTI+c+ATHwIs+XPcircj9WL/tNASH45/phiIvxhPU\n" +
        "z4T6OgUfDVpQEC8SWCuq77aZAhnpZ0rfQ+h6vEv7X970wIAPT/hbyzekyWEFmH5Q\n" +
        "RxSPrOMKF/V0wuTOVJcoHZW5r5cK7xsVe/otdYdOgt67kITS7pqoO1BlstRuOEHL\n" +
        "jhaJ/40dXocrWpDQlJP2TZFwk5JF1Pbx/2mLr/asQapc/qbQP82b+iDLW8QIBT0f\n" +
        "+xi4Js4k7Qo9kZSuUHUCDzmJt6Z0USBxxp/tSZRVjWRaT9ORDrdyfb/mKFSt9BtC\n" +
        "B5VRJ1e0q7P/9/w21T0p9uV3eNXhnPnLFQIDAQAB\n" +
        "-----END RSA PUBLIC KEY-----";
    // fingerprint == 0 -> native layer derives it from the PEM (see ConnectionsManager::applyServerConfig).
    static final long OWPENGRAM_RSA_FINGERPRINT = 0;

    // Official Telegram production RSA key (restored from original Android source)
    static final String TELEGRAM_RSA_KEY =
        "-----BEGIN RSA PUBLIC KEY-----\n" +
        "MIIBCgKCAQEA6LszBcC1LGzyr992NzE0ieY+BSaOW622Aa9Bd4ZHLl+TuFQ4lo4g\n" +
        "5nKaMBwK/BIb9xUfg0Q29/2mgIR6Zr9krM7HjuIcCzFvDtr+L0GQjae9H0pRB2OO\n" +
        "62cECs5HKhT5DZ98K33vmWiLowc621dQuwKWSQKjWf50XYFw42h21P2KXUGyp2y/\n" +
        "+aEyZ+uVgLLQbRA1dEjSDZ2iGRy12Mk5gpYc397aYp438fsJoHIgJ2lgMv5h7WY9\n" +
        "t6N/byY9Nw9p21Og3AoXSL2q/2IJ1WRUhebgAdGVMlV1fkuOQoEzR7EdpqtQD9Cs\n" +
        "5+bfo3Nhmcyvk5ftB0WkJ9z6bNZ7yxrP8wIDAQAB\n" +
        "-----END RSA PUBLIC KEY-----";
    static final long TELEGRAM_RSA_FINGERPRINT = 0xd09d1d85de64fd85L;

    // --- Built-in servers ---

    public static OwpengramServer owpengramServer() {
        OwpengramServer s = new OwpengramServer();
        s.id                 = ID_OWPENGRAM;
        s.name               = "OwpenGram";
        s.description        = "This is a test OwpenGram server.";
        s.host               = DEFAULT_HOST;
        s.port               = DEFAULT_PORT;
        s.isOfficial         = true;
        s.isTelegram         = false;
        s.multiDc            = false;
        s.mainDcId           = 1;
        s.rsaPublicKey       = OWPENGRAM_RSA_KEY;
        s.rsaKeyFingerprint  = OWPENGRAM_RSA_FINGERPRINT;
        return s;
    }

    public static OwpengramServer telegramServer() {
        OwpengramServer s = new OwpengramServer();
        s.id                 = ID_TELEGRAM;
        s.name               = "Telegram";
        s.description        = "Official Telegram cloud. Sign in with your Telegram account.";
        s.host               = "149.154.167.51";
        s.port               = 443;
        s.isOfficial         = true;
        s.isTelegram         = true;
        s.multiDc            = true;
        s.mainDcId           = 2;
        s.rsaPublicKey       = TELEGRAM_RSA_KEY;
        s.rsaKeyFingerprint  = TELEGRAM_RSA_FINGERPRINT;
        return s;
    }

    // --- Server list ---

    public static List<OwpengramServer> listServers() {
        List<OwpengramServer> result = new ArrayList<>();
        result.add(telegramServer());
        result.add(owpengramServer());
        result.addAll(loadCustomServers());
        return result;
    }

    public static OwpengramServer getServerById(String id) {
        if (id == null) return null;
        for (OwpengramServer s : listServers()) {
            if (id.equals(s.id)) return s;
        }
        return null;
    }

    // --- Custom server persistence ---

    public static List<OwpengramServer> loadCustomServers() {
        List<OwpengramServer> result = new ArrayList<>();
        try {
            String json = getPrefs().getString(KEY_CUSTOM_SERVERS, "[]");
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                result.add(OwpengramServer.fromJson(array.getJSONObject(i)));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    private static void saveCustomServers(List<OwpengramServer> customs) {
        try {
            JSONArray array = new JSONArray();
            for (OwpengramServer s : customs) {
                array.put(s.toJson());
            }
            getPrefs().edit().putString(KEY_CUSTOM_SERVERS, array.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void addCustomServer(OwpengramServer server) {
        if (server.id == null || server.id.isEmpty()) {
            server.id = UUID.randomUUID().toString();
        }
        server.isOfficial = false;
        List<OwpengramServer> customs = loadCustomServers();
        customs.add(server);
        saveCustomServers(customs);
    }

    public static void updateCustomServer(OwpengramServer updated) {
        List<OwpengramServer> customs = loadCustomServers();
        for (int i = 0; i < customs.size(); i++) {
            if (updated.id.equals(customs.get(i).id)) {
                customs.set(i, updated);
                saveCustomServers(customs);
                return;
            }
        }
    }

    public static void removeCustomServer(String id) {
        List<OwpengramServer> customs = loadCustomServers();
        for (int i = 0; i < customs.size(); i++) {
            if (id.equals(customs.get(i).id)) {
                customs.remove(i);
                saveCustomServers(customs);
                return;
            }
        }
    }

    // --- Account - server binding ---

    public static String getServerIdForAccount(int accountNum) {
        return getPrefs().getString(KEY_ACCOUNT_SERVER + accountNum, null);
    }

    public static void setServerForAccount(String serverId, int accountNum) {
        getPrefs().edit().putString(KEY_ACCOUNT_SERVER + accountNum, serverId).apply();
    }

    public static OwpengramServer getServerForAccount(int accountNum) {
        return getServerById(getServerIdForAccount(accountNum));
    }

    public static boolean hasServerForAccount(int accountNum) {
        return getServerIdForAccount(accountNum) != null;
    }

    /**
     * Clears the account-slot -> server-id mapping. Must be called on logout:
     * account slots are freed and reused by whatever account logs in next
     * (see UserConfig / ProfileActivity's "Add another account" free-slot
     * scan), but this mapping is keyed by slot number, not by user identity.
     * Without clearing it here, a newly-activated account inheriting a
     * previously-used slot would silently inherit the PREVIOUS occupant's
     * server assignment (wrong category in the account list, wrong DC/RSA
     * key applied) until the user happens to pick a server again.
     */
    public static void clearServerForAccount(int accountNum) {
        getPrefs().edit().remove(KEY_ACCOUNT_SERVER + accountNum).apply();
    }

    /**
     * Stable key for grouping per-server local device state (currently: recent/frequently-used
     * emoji, see Emoji.java) by backend rather than by device. Two different custom servers do
     * NOT share a document-id namespace — a custom-emoji document id cached while using one
     * never resolves on another (or on official Telegram), so state keyed only by device would
     * leak cross-server ids into every account's UI, permanently unresolvable there (see
     * AnimatedEmojiDrawable.EmojiDocumentFetcher — a not-found custom emoji has no negative
     * cache/timeout, it just stays broken forever). Official Telegram, and anything we can't
     * identify a server for, get "" (the original, unsuffixed storage keys) so existing
     * installs' state is preserved unchanged. Host, not server id, is the actual identity that
     * matters here: the same physical backend added twice under different local ids should
     * still share one scope.
     */
    public static String serverScopeKeyForAccount(int accountNum) {
        OwpengramServer s = getServerForAccount(accountNum);
        if (s == null || s.isTelegram || TextUtils.isEmpty(s.host)) {
            return "";
        }
        return s.host.toLowerCase(java.util.Locale.US);
    }

    /**
     * Whether the account's current server exposes Telegram Premium / Stars /
     * Business features. Telegram always does. Any other (custom
     * self-hosted, e.g. gramsrv) server is trusted based on its own appConfig
     * contract — help.getAppConfig `premium_purchase_blocked` / `stars_purchase_blocked`
     * (MessagesController.premiumLocked / starsLocked, refreshed on every appConfig
     * fetch) — the same signal the desktop client relies on via session->premiumPossible(),
     * which has no separate per-server allowlist. A server that doesn't implement these
     * features server-side is expected to send the blocked flags (as the old
     * owpengram-server did); one that does (gramsrv) is not unconditionally hidden here
     * just for not being a hardcoded preset.
     */
    public static boolean serverSupportsPremium(int accountNum) {
        OwpengramServer s = getServerForAccount(accountNum);
        if (s == null) {
            return false;
        }
        if (s.isTelegram) {
            return true;
        }
        MessagesController mc = MessagesController.getInstance(accountNum);
        return !mc.premiumLocked || !mc.starsLocked;
    }

    /**
     * Whether the account's current server is the official Telegram network.
     * Used to hide Telegram-specific help entries (Ask a Question, Telegram FAQ /
     * Features, Privacy Policy) on OwpenGram and other servers, where they don't apply.
     */
    public static boolean serverIsOfficialTelegram(int accountNum) {
        OwpengramServer s = getServerForAccount(accountNum);
        return s != null && s.isTelegram;
    }

    /**
     * Whether the account's current server uses email as the account identity
     * instead of a real phone number (help.getAppConfig `email_signup_enabled`,
     * MessagesController.emailSignupEnabled, refreshed on every appConfig
     * fetch — same mechanism as serverSupportsPremium above). Such a server has
     * no real SMS delivery, so changing to an arbitrary phone number is
     * server-side forbidden; used to pre-empt that with a clear client-side
     * message instead of a confusing RPC error.
     */
    public static boolean serverHasEmailSignup(int accountNum) {
        return MessagesController.getInstance(accountNum).emailSignupEnabled;
    }

    /**
     * True when two account slots target the same server. Used so the duplicate
     * login check (same phone / same user id) only triggers within one server —
     * the same phone number on a different server is a different account.
     * Matches by server id, or by resolved endpoint (host:port + Telegram flag)
     * so two profiles pointing at the same machine are still treated as one.
     */
    public static boolean sameAccountServer(int accountA, int accountB) {
        String idA = getServerIdForAccount(accountA);
        String idB = getServerIdForAccount(accountB);
        if (java.util.Objects.equals(idA, idB)) {
            return true;
        }
        OwpengramServer sa = getServerById(idA);
        OwpengramServer sb = getServerById(idB);
        if (sa == null || sb == null) {
            return false;
        }
        return sa.isTelegram == sb.isTelegram
                && sa.port == sb.port
                && sa.host != null && sa.host.equals(sb.host);
    }

    // --- DC application ---

    /**
     * Applies the selected server to the given account, atomically on the network
     * thread. Mirrors desktop ApplyServerToDcOptions + RestoreServerToAccount:
     *   - Telegram:       restore real Telegram DC 1-5, unlock, Telegram RSA key
     *   - Single-server:  map DC 1-5 all to one host:port, lock, server RSA key
     *
     * resetKeys=true clears auth keys (user picked a NEW server -> force re-handshake);
     * resetKeys=false keeps them (startup restore -> reuse the existing session).
     */
    public static void applyServerToAccount(OwpengramServer server, int accountNum) {
        applyServerToAccount(server, accountNum, true);
    }

    public static void applyServerToAccount(OwpengramServer server, int accountNum, boolean resetKeys) {
        if (server == null) return;
        final String key;
        final long fingerprint;
        if (server.isTelegram) {
            key = TELEGRAM_RSA_KEY;
            fingerprint = TELEGRAM_RSA_FINGERPRINT;
        } else if (server.rsaPublicKey != null && !server.rsaPublicKey.isEmpty()) {
            // Custom server with its own RSA key. Pass fingerprint 0 so the native
            // layer derives it from the PEM (we can't compute it in Java).
            key = server.rsaPublicKey;
            fingerprint = (server.rsaKeyFingerprint != 0) ? server.rsaKeyFingerprint : 0;
        } else {
            // No key provided -> shared default owpengram key (fingerprint known).
            key = OWPENGRAM_RSA_KEY;
            fingerprint = OWPENGRAM_RSA_FINGERPRINT;
        }
        int mainDc = server.mainDcId > 0
                ? server.mainDcId
                : (server.isTelegram ? 2 : 1);
        ConnectionsManager.native_applyServerConfig(
                accountNum,
                server.host,
                server.port,
                server.isTelegram,
                mainDc,
                key,
                fingerprint,
                resetKeys);
    }

    /**
     * Called from ApplicationLoader after each account's ConnectionsManager is
     * initialized, to restore the previously selected server config. Does NOT
     * reset auth keys so an already-authorized session keeps working.
     */
    public static void applyStoredServerToAccount(int accountNum) {
        OwpengramServer server = getServerForAccount(accountNum);
        if (server != null) {
            applyServerToAccount(server, accountNum, false);
        }
    }

    // --- Helpers ---

    private static SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Returns true when at least one active account uses a Telegram-official server. */
    public static boolean anyAccountIsTelegram() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                OwpengramServer s = getServerForAccount(a);
                if (s != null && s.isTelegram) return true;
            }
        }
        return false;
    }

    /**
     * Self-hosted invite/username link routing (owpg scheme). Finds an activated
     * account that owns the given link host, i.e. whose me_url_prefix host
     * (MessagesController.linkPrefix) equals {@code host} AND whose server explicitly
     * advertises owpengram=true in help.getAppConfig (so the official Telegram network
     * and other forks on a shared host are excluded). Prefers the currently
     * selected account when it matches. Returns the account index, or -1 if none.
     * Mirrors the desktop OwpengramHostForUrl / OwpengramAccountHost.
     */
    public static int findOwpengramAccountForHost(String host) {
        if (TextUtils.isEmpty(host)) {
            return -1;
        }
        host = host.toLowerCase();
        // Prefer the active account so a link is opened on the account the user is on.
        int selected = UserConfig.selectedAccount;
        if (accountOwnsHost(selected, host)) {
            return selected;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (a != selected && accountOwnsHost(a, host)) {
                return a;
            }
        }
        return -1;
    }

    private static boolean accountOwnsHost(int accountNum, String host) {
        if (!UserConfig.getInstance(accountNum).isClientActivated()) {
            return false;
        }
        // owpg links never target the official Telegram network (t.me handling applies there).
        if (serverIsOfficialTelegram(accountNum)) {
            return false;
        }
        MessagesController controller = MessagesController.getInstance(accountNum);
        // Primary: the host matches this account's me_url_prefix (link prefix).
        String prefix = controller.linkPrefix;
        if (prefix != null && host.equalsIgnoreCase(prefix)) {
            return true;
        }
        // Fallback: the host matches the account's actual server endpoint host. Covers
        // owpg://<server-ip-or-domain>/... even before me_url_prefix / the appConfig
        // owpengram marker have been refetched (e.g. after the admin changed the prefix).
        OwpengramServer server = getServerForAccount(accountNum);
        if (server != null && server.host != null && host.equalsIgnoreCase(server.host)) {
            return true;
        }
        return false;
    }

    /** Returns the first activated account whose server is isTelegram, or -1. */
    public static int firstTelegramAccount() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                OwpengramServer s = getServerForAccount(a);
                if (s != null && s.isTelegram) return a;
            }
        }
        return -1;
    }

    /**
     * Display name of the server an account is on, or null if unknown. Used for
     * the main app header so it shows the current server instead of "Telegram".
     */
    public static String serverNameForAccount(int accountNum) {
        OwpengramServer s = getServerForAccount(accountNum);
        return (s != null) ? s.name : null;
    }

    // --- Account limits (mirrors desktop: premium cap is Telegram-only) ---

    /** First unused account slot (any server), or -1 if all slots are taken. */
    public static int firstFreeAccountSlot() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                return a;
            }
        }
        return -1;
    }

    /** Number of activated accounts on a Telegram-official server. */
    public static int countTelegramAccounts() {
        int n = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                OwpengramServer s = getServerForAccount(a);
                if (s != null && s.isTelegram) n++;
            }
        }
        return n;
    }

    // Telegram-official account caps, mirroring the desktop client:
    //   kMaxAccounts = 3 (free), kPremiumMaxAccounts = 6 (with premium).
    private static final int TELEGRAM_FREE_LIMIT = 3;
    private static final int TELEGRAM_PREMIUM_LIMIT = 6;

    /**
     * Telegram-account cap (premium raises it from 3 to 6). Only Telegram accounts
     * count toward this; custom/self-hosted accounts are limited solely by the hard
     * total slot count (MAX_ACCOUNT_COUNT).
     */
    public static int telegramAccountLimit() {
        int limit = UserConfig.hasPremiumOnAccounts()
                ? TELEGRAM_PREMIUM_LIMIT
                : TELEGRAM_FREE_LIMIT;
        return Math.min(limit, UserConfig.MAX_ACCOUNT_COUNT);
    }

    /** True if another Telegram account can still be added under the premium cap. */
    public static boolean canAddTelegramAccount() {
        return countTelegramAccounts() < telegramAccountLimit();
    }

    public static class ServerInfoFetchResult {
        public final String rsaPublicKeyPem;
        public final int dcId;
        // name/description/hasIcon are admin-edited on the server (Server
        // Settings' identity section) and optional -- empty/false means the
        // operator hasn't set them, not that the server failed to answer.
        public final String name;
        public final String description;
        public final boolean hasIcon;

        public ServerInfoFetchResult(String rsaPublicKeyPem, int dcId, String name, String description, boolean hasIcon) {
            this.rsaPublicKeyPem = rsaPublicKeyPem;
            this.dcId = dcId;
            this.name = name;
            this.description = description;
            this.hasIcon = hasIcon;
        }
    }

    /**
     * Fetches a server's RSA public key, home DC id, and identity
     * (name/description/icon presence) from its well-known same-port HTTP
     * endpoint (GET host:port/owpengram/server-info), so "Add Server" can be
     * filled in from just host:port instead of manual PEM copy-paste +
     * guessing the DC id. callback receives the result on success, or null
     * on any failure (offline, unsupported server, malformed response) --
     * always on the UI thread (AsyncTask#onPostExecute).
     */
    public static void fetchServerInfo(String host, int port, org.telegram.messenger.Utilities.Callback<ServerInfoFetchResult> callback) {
        if (TextUtils.isEmpty(host) || port <= 0) {
            callback.run(null);
            return;
        }
        String url = "http://" + host + ":" + port + "/owpengram/server-info";
        new org.telegram.ui.web.HttpGetTask(body -> {
            if (body == null) {
                callback.run(null);
                return;
            }
            try {
                JSONObject obj = new JSONObject(body);
                String pem = obj.optString("rsa_public_key_pem", "");
                int dcId = obj.optInt("dc_id", 0);
                String name = obj.optString("name", "");
                String description = obj.optString("description", "");
                boolean hasIcon = obj.optBoolean("has_icon", false);
                callback.run(pem.isEmpty() ? null : new ServerInfoFetchResult(pem, dcId, name, description, hasIcon));
            } catch (Exception e) {
                callback.run(null);
            }
        }).execute(url);
    }

    private static final String SERVER_LOGOS_DIR = "owpengram_server_logos";

    /**
     * Fetches a server's icon (GET host:port/owpengram/server-icon) -- only
     * worth calling when a prior fetchServerInfo answered with hasIcon=true.
     * callback receives the decoded bitmap on success, or null on any
     * failure -- always on the UI thread (AsyncTask#onPostExecute).
     */
    public static void fetchServerIcon(String host, int port, org.telegram.messenger.Utilities.Callback<Bitmap> callback) {
        if (TextUtils.isEmpty(host) || port <= 0) {
            callback.run(null);
            return;
        }
        String url = "http://" + host + ":" + port + "/owpengram/server-icon";
        new org.telegram.ui.web.HttpGetBitmapTask(callback::run).execute(url);
    }

    /**
     * Persists a server icon bitmap -- auto-fetched from the server or
     * picked locally by the user (see AddServerFragment.pickIcon /
     * applyIconBitmap) -- under the app's own files directory (mirrors the
     * desktop client's owpengram_server_logos/ convention) and returns the
     * absolute path to store as OwpengramServer.logoPath. Unlike a cache-dir
     * temp file, this survives Android reclaiming cache space, since it's
     * the server's permanent logo once saved.
     */
    public static String saveFetchedIcon(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), SERVER_LOGOS_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            FileLog.e("OwpengramServers: failed to create " + dir);
            return null;
        }
        File file = new File(dir, "fetched_" + System.currentTimeMillis() + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
        return file.getAbsolutePath();
    }
}
