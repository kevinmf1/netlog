package io.github.kevinmf1.netlog.desktop;

import com.google.gson.JsonObject;
import org.junit.Test;
import org.junit.Assume;
import javax.swing.*;
import java.awt.*;
import java.lang.reflect.*;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import static org.junit.Assert.*;

public class ViewTest {
    @SuppressWarnings("unchecked") private static <T> T field(NetLogDesktop app, String name) throws Exception {
        Field field = NetLogDesktop.class.getDeclaredField(name); field.setAccessible(true); return (T) field.get(app);
    }
    private static JsonObject request(int id, String method, String app) {
        JsonObject record = new JsonObject();
        record.addProperty("id", id); record.addProperty("time", id); record.addProperty("key", "test/session/" + id);
        record.addProperty("session", "session"); record.addProperty("device", "test"); record.addProperty("app", app);
        record.addProperty("method", method); record.addProperty("status", 200); record.addProperty("url", "https://example.test/" + id);
        return record;
    }
    private static void ingest(NetLogDesktop app, JsonObject... records) throws Exception {
        Method method = NetLogDesktop.class.getDeclaredMethod("ingest", AdbBridge.Batch.class); method.setAccessible(true);
        method.invoke(app, new AdbBridge.Batch(List.of(records), "Component test"));
    }
    @Test public void filtersWithoutLosingSelectionAndClearIgnoresOldUpdates() throws Exception {
        Assume.assumeFalse("Requires a graphics environment for an invisible JFrame", GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            NetLogDesktop app = new NetLogDesktop();
            try {
                JTable table = field(app, "table"); JTextField search = field(app, "search");
                JComboBox<?> apps = field(app, "applications"); JButton pause = field(app, "pause"), clear = field(app, "clear");
                ingest(app, request(1, "GET", "One"), request(2, "POST", "Two"));
                assertEquals(2, table.getRowCount()); assertEquals("POST", table.getValueAt(table.getSelectedRow(), 1));
                Object previousModel = apps.getModel();
                ingest(app, request(1, "GET", "One"));
                assertSame(previousModel, apps.getModel()); assertEquals("POST", table.getValueAt(table.getSelectedRow(), 1));
                search.setText("get"); assertEquals(1, table.getRowCount()); assertEquals("GET", table.getValueAt(0, 1));
                search.setText("missing"); assertEquals(0, table.getRowCount());
                search.setText(""); apps.setSelectedItem("One / test"); assertEquals(1, table.getRowCount());
                apps.setSelectedIndex(0); pause.doClick(); ingest(app, request(3, "GET", "One")); assertEquals(2, table.getRowCount());
                pause.doClick(); assertEquals(3, table.getRowCount());
                clear.doClick(); ingest(app, request(2, "POST", "Two")); assertEquals(0, table.getRowCount());
                ingest(app, request(4, "GET", "One")); assertEquals(1, table.getRowCount());
            } catch (Exception error) { throw new RuntimeException(error); }
            finally {
                try { ((ScheduledExecutorService) field(app, "worker")).shutdownNow(); ((JFrame) field(app, "frame")).dispose(); }
                catch (Exception error) { throw new RuntimeException(error); }
            }
        });
    }
}
