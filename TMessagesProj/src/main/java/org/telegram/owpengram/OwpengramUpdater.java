package org.telegram.owpengram;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds out whether a newer OwpenGram release is published on GitHub, the same
 * source the desktop client's updater reads, and hands it to the stock update
 * dialog as an ordinary help.appUpdate.
 *
 * It asks GitHub rather than a server on purpose: help.getAppUpdate goes to
 * whichever server the active account lives on, and for a Telegram account that
 * is Telegram itself, which would offer its own app instead of ours.
 *
 * Releases are tagged O1, O2, ...; the running build knows its own number
 * (OWPENGRAM_BUILD, given to the build script for every release; 0 = a local
 * dev build, which never checks).
 */
public final class OwpengramUpdater {

    private static final String LATEST_RELEASE_URL = "https://api.github.com/repos/owpengram/owpengram-android-client/releases/latest";
    private static final long CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000;
    private static final long PROMPT_INTERVAL_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_NOTES_CHARS = 4000;
    private static final Pattern TAG = Pattern.compile("^[Oo](\\d+)$");

    private static final String PREFS = "owpengram_updater";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_PROMPTED_TAG = "prompted_tag";
    private static final String KEY_PROMPTED_AT = "prompted_at";

    public interface Callback {
        /**
         * Runs on the UI thread. {@code update} is null when there is nothing to
         * offer; {@code failed} tells "could not ask" apart from "up to date".
         */
        void onResult(TLRPC.TL_help_appUpdate update, boolean failed);
    }

    private static boolean checking;

    private OwpengramUpdater() {
    }

    /** Only the direct-download build updates itself this way; store builds leave it to the store. */
    public static boolean enabled() {
        return ApplicationLoader.isStandaloneBuild() && BuildVars.OWPENGRAM_BUILD > 0;
    }

    public static void check(boolean force, Callback callback) {
        final SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final long now = System.currentTimeMillis();
        if (!force && Math.abs(now - prefs.getLong(KEY_LAST_CHECK, 0)) < CHECK_INTERVAL_MS) {
            AndroidUtilities.runOnUIThread(() -> callback.onResult(null, false));
            return;
        }
        synchronized (OwpengramUpdater.class) {
            if (checking) {
                AndroidUtilities.runOnUIThread(() -> callback.onResult(null, true));
                return;
            }
            checking = true;
        }
        Utilities.globalQueue.postRunnable(() -> {
            TLRPC.TL_help_appUpdate update = null;
            boolean failed = false;
            try {
                update = fetchNewerRelease();
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
            } catch (Exception e) {
                // an update check is advisory: no network, GitHub rate limit or a
                // malformed answer must never reach the user as an error dialog
                FileLog.e(e);
                failed = true;
            } finally {
                synchronized (OwpengramUpdater.class) {
                    checking = false;
                }
            }
            if (update != null && !force && alreadyPrompted(prefs, update.version)) {
                update = null;
            }
            if (update != null) {
                prefs.edit()
                        .putString(KEY_PROMPTED_TAG, update.version)
                        .putLong(KEY_PROMPTED_AT, System.currentTimeMillis())
                        .apply();
            }
            final TLRPC.TL_help_appUpdate result = update;
            final boolean failedResult = failed;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(result, failedResult));
        });
    }

    /** The same release is not offered again within a day unless the user asked to check. */
    private static boolean alreadyPrompted(SharedPreferences prefs, String tag) {
        return tag.equals(prefs.getString(KEY_PROMPTED_TAG, null))
                && Math.abs(System.currentTimeMillis() - prefs.getLong(KEY_PROMPTED_AT, 0)) < PROMPT_INTERVAL_MS;
    }

    private static TLRPC.TL_help_appUpdate fetchNewerRelease() throws Exception {
        final HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE_URL).openConnection();
        try {
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "OwpenGram-Android/" + BuildVars.OWPENGRAM_BUILD);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("GitHub answered " + connection.getResponseCode());
            }
            return parseRelease(readBounded(connection.getInputStream()));
        } finally {
            connection.disconnect();
        }
    }

    private static String readBounded(InputStream in) throws Exception {
        try (InputStream stream = in) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("release answer too large");
                }
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    /** Turns GitHub's release JSON into the dialog's payload, or null when there is nothing newer to offer. */
    static TLRPC.TL_help_appUpdate parseRelease(String json) throws Exception {
        final JSONObject release = new JSONObject(json);
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) {
            return null;
        }
        final String tag = release.optString("tag_name", "");
        final Matcher matcher = TAG.matcher(tag);
        if (!matcher.matches()) {
            return null;
        }
        final int build = Integer.parseInt(matcher.group(1));
        if (build <= BuildVars.OWPENGRAM_BUILD) {
            return null;
        }

        String url = null;
        final JSONArray assets = release.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            final JSONObject asset = assets.optJSONObject(i);
            if (asset != null && asset.optString("name", "").toLowerCase().endsWith(".apk")) {
                url = asset.optString("browser_download_url", null);
                break;
            }
        }
        if (url == null || url.isEmpty()) {
            // no APK attached yet: send the person to the release page instead
            url = release.optString("html_url", null);
        }
        if (url == null || !url.startsWith("https://")) {
            return null;
        }

        final TLRPC.TL_help_appUpdate update = new TLRPC.TL_help_appUpdate();
        update.id = build;
        // shown as "Version O8" in the dialog: built from the release number, not copied from the tag text
        update.version = "O" + build;
        update.text = notes(release.optString("body", ""));
        update.url = url;
        update.flags |= 4; // url
        return update;
    }

    private static String notes(String body) {
        String text = body == null ? "" : body.replace("\r\n", "\n").trim();
        // the dialog shows plain text: drop the markdown markers that would show up as noise
        text = text.replaceAll("(?m)^#{1,6}\\s*", "").replace("**", "").replace("__", "");
        if (text.length() > MAX_NOTES_CHARS) {
            text = text.substring(0, MAX_NOTES_CHARS).trim() + "…";
        }
        return text;
    }
}
