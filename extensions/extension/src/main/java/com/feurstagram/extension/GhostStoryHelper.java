package com.feurstagram.extension;

import android.content.Context;
import android.util.Log;

import java.util.Locale;

public final class GhostStoryHelper {
    private static final String TAG = "FeurGhostStory";
    private static final String PREF_NAME = "feur_ghost_story_prefs";
    private static final String KEY_ENABLED = "ghost_story_enabled";

    private GhostStoryHelper() {}

    public static boolean isEnabled(Context context) {
        if (context == null) return true;
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        if (context == null) return;
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
    }

    /**
     * Dùng khi điểm hook chỉ truyền vào URL của request gửi đi.
     */
    public static boolean shouldBlockStorySeen(Context context, String requestUrl) {
        return shouldBlockStorySeen(context, requestUrl, null);
    }

    /**
     * Dùng khi điểm hook có cả URL và nội dung body (hỗ trợ chặn thêm GraphQL mutation).
     */
    public static boolean shouldBlockStorySeen(Context context, String requestUrl, String requestBody) {
        if (!isEnabled(context)) return false;

        if (requestUrl != null) {
            String lowerUrl = requestUrl.toLowerCase(Locale.ROOT);
            if (lowerUrl.contains("/media/seen")
                    || lowerUrl.contains("reel_media_seen")
                    || lowerUrl.contains("/stories/seen")
                    || lowerUrl.contains("story_seen")) {
                Log.d(TAG, "Blocked story seen URL: " + requestUrl);
                return true;
            }
        }

        if (requestBody != null && !requestBody.isEmpty()) {
            String lowerBody = requestBody.toLowerCase(Locale.ROOT);
            if (lowerBody.contains("storiesv3seenmutation")
                    || lowerBody.contains("storiesseenmutation")
                    || lowerBody.contains("igstoryseenmutation")
                    || lowerBody.contains("reel_media_seen")) {
                Log.d(TAG, "Blocked story seen GraphQL payload");
                return true;
            }
        }

        return false;
    }
}
