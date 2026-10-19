package io.github.kevinmf1.netlog.desktop;
import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PresentationTest {
    @Test public void curlQuotesShellInputAndKeepsRedactions() {
        JsonObject record = JsonParser.parseString("{\"method\":\"POST\",\"url\":\"https://example.test/a'$(touch nope)\",\"requestHeaders\":[[\"Authorization\",\"[REDACTED]\"]],\"requestBody\":\"hello\"}").getAsJsonObject();
        String command = NetLogDesktop.curl(record);
        assertTrue(command.contains("'\"'\"'")); assertTrue(command.contains("Authorization: [REDACTED]")); assertTrue(command.contains("--data-raw 'hello'"));
    }
    @Test public void detailsPreserveDuplicateHeadersAndShowTruncation() {
        JsonObject record = JsonParser.parseString("{\"responseHeaders\":[[\"Vary\",\"Origin\"],[\"Vary\",\"Accept\"]],\"responseBody\":\"{\\\"ok\\\":true}\",\"responseTruncated\":true}").getAsJsonObject();
        String value = NetLogDesktop.detail(record, "response");
        assertTrue(value.contains("Vary: Origin\nVary: Accept")); assertTrue(value.contains("Truncated at 64 KiB")); assertTrue(value.contains("\"ok\": true"));
    }
}
