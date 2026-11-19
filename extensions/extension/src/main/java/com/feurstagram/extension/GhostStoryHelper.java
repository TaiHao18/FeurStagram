package com.feurstagram.extension;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

public final class GhostStoryHelper {
    private static final String TAG = "FeurGhostStory";
    private static final String PREF_NAME = "feur_ghost_story_prefs";
    private static final String KEY_ENABLED = "ghost_story_enabled";
    private static volatile Context cachedContext;

    private GhostStoryHelper() {}

    private static Context resolveContext(Context context) {
        if (context != null) return context.getApplicationContext();
        if (cachedContext != null) return cachedContext;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                cachedContext = ((Context) app).getApplicationContext();
                return cachedContext;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static boolean isEnabled(Context context) {
        Context appCtx = resolveContext(context);
        if (appCtx == null) return true;
        return appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        Context appCtx = resolveContext(context);
        if (appCtx == null) return;
        appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
    }

    /**
     * Được gọi trực tiếp từ TigonServiceLayer.startRequest* thông qua NetworkBlockPatch.
     * Ném IOException để Tigon tự hủy request báo đã xem Story trước khi gửi lên máy chủ.
     */
    public static void checkAndBlock(URI uri) throws IOException {
        if (uri == null) return;
        String url = uri.toString();
        if (shouldBlockStorySeen(null, url)) {
            throw new IOException("Blocked story seen request");
        }
    }

    public static boolean shouldBlockStorySeen(Context context, String requestUrl) {
        return shouldBlockStorySeen(context, requestUrl, null);
    }

    public static boolean shouldBlockStorySeen(Context context, String requestUrl, String requestBody) {
        if (!isEnabled(context)) return false;

        if (requestUrl != null) {
            String lowerUrl = requestUrl.toLowerCase(Locale.ROOT);
            if (lowerUrl.contains("/media/seen")
                    || lowerUrl.contains("reel_media_seen")
                    || lowerUrl.contains("/stories/seen")
                    || lowerUrl.contains("story_seen")) {
                try {
                    Log.i(TAG, "Blocked story seen URL: " + requestUrl);
                } catch (Throwable ignored) {}
                return true;
            }
        }

        if (requestBody != null && !requestBody.isEmpty()) {
            String lowerBody = requestBody.toLowerCase(Locale.ROOT);
            if (lowerBody.contains("storiesv3seenmutation")
                    || lowerBody.contains("storiesseenmutation")
                    || lowerBody.contains("igstoryseenmutation")
                    || lowerBody.contains("reel_media_seen")) {
                try {
                    Log.i(TAG, "Blocked story seen GraphQL payload");
                } catch (Throwable ignored) {}
                return true;
            }
        }

        return false;
    }
}
