package com.feurstagram.extension;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

public final class NoteTrackerHelper {
    private static final String TAG = "FeurNoteTracker";
    private static final String PREF_NAME = "feur_note_tracker_prefs";
    private static final String KEY_ENABLED = "note_tracker_enabled";
    private static final String CHANNEL_ID = "feur_ig_note_alert";

    // Điền danh sách username cần theo dõi (viết thường toàn bộ)
    private static final Set<String> TARGET_USERS = new HashSet<>(Arrays.asList(
            "haoj.taii"
    ));

    private NoteTrackerHelper() {}

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
     * Gọi hàm này ngay khi nhận được chuỗi JSON phản hồi từ các API inbox / notes.
     */
    public static void inspectResponse(Context context, String rawJson) {
        if (context == null || rawJson == null || rawJson.isEmpty()) return;
        if (!isEnabled(context)) return;

        // Lọc nhanh để không tốn CPU phân tích các gói tin không chứa tài khoản mục tiêu
        String lowerRaw = rawJson.toLowerCase(Locale.ROOT);
        boolean hasTarget = false;
        for (String target : TARGET_USERS) {
            if (lowerRaw.contains(target)) {
                hasTarget = true;
                break;
            }
        }
        if (!hasTarget) return;

        try {
            String cleaned = rawJson.trim();
            // Bỏ tiền tố chống JSON hijacking nếu Meta trả về dạng "for (;;);"
            if (cleaned.startsWith("for (;;);")) {
                cleaned = cleaned.substring(9).trim();
            }

            if (cleaned.startsWith("{")) {
                traverseJson(context, new JSONObject(cleaned));
            } else if (cleaned.startsWith("[")) {
                traverseJson(context, new JSONArray(cleaned));
            }
        } catch (Throwable t) {
            // Bỏ qua an toàn nếu dữ liệu trả về không phải JSON chuẩn
            Log.d(TAG, "Skip non-JSON response: " + t.getMessage());
        }
    }

    private static void traverseJson(Context context, Object node) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            tryExtractNote(context, obj);

            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object child = obj.opt(key);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    traverseJson(context, child);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (item instanceof JSONObject || item instanceof JSONArray) {
                    traverseJson(context, item);
                }
            }
        }
    }

    private static void tryExtractNote(Context context, JSONObject obj) {
        // Nhận diện khối dữ liệu Note: có chứa user/author kèm text hoặc thông tin nhạc
        boolean looksLikeNote = obj.has("note_response_info")
                || obj.has("music_note_response_info")
                || obj.has("note_id")
                || (obj.has("text") && (obj.has("user") || obj.has("author")));

        if (!looksLikeNote) return;

        String username = extractUsername(obj);
        if (username == null || !TARGET_USERS.contains(username)) return;

        String noteText = obj.optString("text", "").trim();
        String musicLabel = extractMusicLabel(obj);

        StringBuilder displayBuilder = new StringBuilder();
        if (!noteText.isEmpty()) {
            displayBuilder.append(noteText);
        }
        if (!musicLabel.isEmpty()) {
            if (displayBuilder.length() > 0) displayBuilder.append(" | ");
            displayBuilder.append("🎵 ").append(musicLabel);
        }

        String displayContent = displayBuilder.toString().trim();
        if (displayContent.isEmpty()) return;

        String noteId = obj.optString("id", obj.optString("note_id", ""));
        String signature = noteId + "::" + displayContent;

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String prefKey = "last_sig_" + username;
        String lastSignature = prefs.getString(prefKey, "");

        if (!signature.equals(lastSignature)) {
            prefs.edit().putString(prefKey, signature).apply();
            Log.i(TAG, "New note detected from " + username + ": " + displayContent);
            sendNotification(context, username, displayContent);
        }
    }

    private static String extractUsername(JSONObject obj) {
        JSONObject userObj = obj.optJSONObject("user");
        if (userObj == null) userObj = obj.optJSONObject("author");

        String raw = userObj != null
                ? userObj.optString("username", "")
                : obj.optString("username", "");

        raw = raw.trim().toLowerCase(Locale.ROOT);
        return raw.isEmpty() ? null : raw;
    }

    private static String extractMusicLabel(JSONObject obj) {
        JSONObject info = obj.optJSONObject("note_response_info");
        JSONObject musicNote = info != null
                ? info.optJSONObject("music_note_response_info")
                : obj.optJSONObject("music_note_response_info");

        if (musicNote == null) return "";

        JSONObject musicInfo = musicNote.optJSONObject("music_info");
        if (musicInfo == null) return "";

        JSONObject asset = musicInfo.optJSONObject("music_asset_info");
        if (asset == null) return "";

        String title = asset.optString("title", "").trim();
        String artist = asset.optString("display_artist", "").trim();

        if (!title.isEmpty() && !artist.isEmpty()) {
            return title + " - " + artist;
        }
        return !title.isEmpty() ? title : artist;
    }

    private static void sendNotification(Context context, String username, String content) {
        try {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "Instagram Note Tracker",
                        NotificationManager.IMPORTANCE_HIGH
                );
                channel.enableVibration(true);
                nm.createNotificationChannel(channel);
            }

            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(context, CHANNEL_ID)
                    : new Notification.Builder(context);

            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(username + " vừa đăng Ghi chú mới")
                    .setContentText(content)
                    .setStyle(new Notification.BigTextStyle().bigText(content))
                    .setAutoCancel(true);

            nm.notify(username.hashCode(), builder.build());
        } catch (Throwable t) {
            Log.e(TAG, "Failed to post notification: " + t.getMessage());
        }
    }
}
