package io.github.kevinmf1.netlog;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CaptureStore {
    static final CaptureStore INSTANCE = new CaptureStore();
    private final LinkedHashMap<Long, String> records = new LinkedHashMap<>();
    private final Map<Long, Long> revisions = new LinkedHashMap<>();
    private long revision;
    private int characters;

    synchronized void put(long id, JSONObject record) {
        try {
            record.put("revision", ++revision);
            String value = record.toString();
            String old = records.put(id, value);
            characters += value.length() - (old == null ? 0 : old.length());
            revisions.put(id, revision);
            while (records.size() > 200 || characters > 2 * 1024 * 1024) {
                Long first = records.keySet().iterator().next();
                characters -= records.remove(first).length();
                revisions.remove(first);
            }
        } catch (Exception ignored) { /* Capture must not change a network call's outcome. */ }
    }

    synchronized List<String> since(long cursor) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<Long, String> entry : records.entrySet()) {
            if (revisions.get(entry.getKey()) > cursor) result.add(entry.getValue());
        }
        result.add("{\"type\":\"end\",\"cursor\":" + revision + "}");
        return result;
    }
}
