package io.github.kevinmf1.netlog.desktop;
import com.google.gson.*;
import org.junit.Test;
import javax.swing.*;
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

    @Test public void cmdShortcutsSelectAllAndCopyOnMac() throws Exception {
        LookAndFeel previous = UIManager.getLookAndFeel();
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            NetLogDesktop.mirrorShortcuts(java.awt.event.InputEvent.META_DOWN_MASK);
            JTextArea area = new JTextArea(); area.setEditable(false);
            assertEquals("select-all", area.getInputMap().get(KeyStroke.getKeyStroke("meta A")));
            assertEquals("copy-to-clipboard", area.getInputMap().get(KeyStroke.getKeyStroke("meta C")));
            assertEquals("Ctrl shortcuts keep working", "select-all", area.getInputMap().get(KeyStroke.getKeyStroke("ctrl A")));
            assertEquals("paste-from-clipboard", new JTextField().getInputMap().get(KeyStroke.getKeyStroke("meta V")));
        } finally { UIManager.setLookAndFeel(previous); }
    }
}
