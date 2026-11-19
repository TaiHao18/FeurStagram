package com.feurstagram.extension;

import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/** Filters delivery JSON after Tigon receives it, without intercepting transport. */
public final class ResponseFilter {
    private ResponseFilter() {}

    // A dispatcher belongs to one request, including its ordered body callbacks.
    // Weak keys also release state if cancellation never dispatches a terminal event.
    private static final Map<Object, State> RESPONSES =
            Collections.synchronizedMap(new WeakHashMap<Object, State>());

    // Replaced with direct, version-resolved field reads by the patch.
    static URI requestUri(Object request) { return null; }
    static int responseStatus(Object response) { return 0; }

    private static boolean tracksNotes(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getPath() == null) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!host.equals("instagram.com") && !host.endsWith(".instagram.com")) return false;
        String path = uri.getPath().toLowerCase(Locale.ROOT);
        return path.contains("/notes/")
                || path.contains("/direct_v2/inbox")
                || path.contains("/graphql");
    }

    public static void onResponse(Object dispatcher, Object response, Object request) {
        URI uri = requestUri(request);
        int status = responseStatus(response);
        boolean ok = status >= 200 && status < 300;
        boolean hide = ok && Block.hidesResponse(uri);
        boolean trackNotes = ok && tracksNotes(uri);
        if (hide || trackNotes || DebugBridge.isNetworkTraceEnabled()) {
            RESPONSES.put(dispatcher, new State(uri, status, hide, trackNotes));
        } else {
            RESPONSES.remove(dispatcher);
        }
    }

    public static ByteBuffer onBody(Object dispatcher, Object request, ByteBuffer body) {
        State state = RESPONSES.get(dispatcher);
        if (state == null || body == null) return body;
        state.received += body.remaining();
        if (state.noteBuffer != null && !state.noteCaptureAborted) {
            state.appendNoteChunk(body);
        }
        ByteBuffer delivered = state.filter == null ? body : state.filter.filter(body);
        state.delivered += delivered.remaining();
        return delivered;
    }

    public static void onComplete(Object dispatcher) { finish(dispatcher, "complete"); }
    public static void onError(Object dispatcher) { finish(dispatcher, "error"); }

    private static void finish(Object dispatcher, String event) {
        State state = RESPONSES.remove(dispatcher);
        if (state != null && "complete".equals(event)) {
            if (state.noteBuffer != null && !state.noteCaptureAborted && state.noteBuffer.size() > 0) {
                try {
                    String rawJson = new String(state.noteBuffer.toByteArray(), StandardCharsets.UTF_8);
                    NoteTrackerHelper.inspectResponse(null, rawJson);
                } catch (Throwable ignored) {}
            }
            DebugBridge.recordResponse(state.path, state.status, state.received, state.delivered,
                    state.filter == null ? 0 : state.filter.removed);
        }
        if (state != null && DebugBridge.isNetworkTraceEnabled()) {
            Log.i("FeurResponse", event + " http=" + state.status + " received=" + state.received
                    + " delivered=" + state.delivered + " removed="
                    + (state.filter == null ? 0 : state.filter.removed) + " " + state.path
                    + (state.filter == null ? "" : " keys=" + state.filter.keySummary()));
        }
    }

    private static final class State {
        private static final int MAX_NOTE_CAPTURE_BYTES = 1024 * 1024; // 1 MB
        final String path;
        final int status;
        final JsonContentFilter filter;
        final ByteArrayOutputStream noteBuffer;
        boolean noteCaptureAborted;
        long received, delivered;

        State(URI uri, int status, boolean hide, boolean trackNotes) {
            path = uri == null ? "" : uri.getPath();
            this.status = status;
            filter = hide ? new JsonContentFilter(path.contains("/ads/graphql")) : null;
            noteBuffer = trackNotes ? new ByteArrayOutputStream(4096) : null;
        }

        void appendNoteChunk(ByteBuffer source) {
            ByteBuffer dup = source.duplicate();
            int len = dup.remaining();
            if (len <= 0) return;
            if (noteBuffer.size() + len > MAX_NOTE_CAPTURE_BYTES) {
                noteCaptureAborted = true;
                noteBuffer.reset();
                return;
            }
            byte[] chunk = new byte[len];
            dup.get(chunk);
            if (noteBuffer.size() == 0) {
                int i = 0;
                while (i < len && (chunk[i] == ' ' || chunk[i] == '\n' || chunk[i] == '\r' || chunk[i] == '\t')) {
                    i++;
                }
                // Bỏ qua ngay nếu payload là dữ liệu nhị phân/nén, không phải JSON
                if (i < len && chunk[i] != '{' && chunk[i] != '[' && chunk[i] != 'f') {
                    noteCaptureAborted = true;
                    return;
                }
            }
            noteBuffer.write(chunk, 0, len);
        }
    }
}
