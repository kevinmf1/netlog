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
    @Test public void rendersBothThemesAtMinimumWindowSize() throws Exception {
        Assume.assumeFalse("Requires a graphics environment for an invisible JFrame", GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            NetLogDesktop app = new NetLogDesktop();
            try {
                JFrame frame = field(app, "frame"); frame.setSize(1000, 640);
                JsonObject record = request(1, "GET", "Component test");
                record.addProperty("url", "https://example.test/api/products?page=1");
                record.addProperty("durationMs", 125);
                ingest(app, record);
                frame.addNotify(); frame.validate();
                for (String mode : List.of("light", "dark")) {
                    if (mode.equals("dark")) ((JButton) field(app, "theme")).doClick();
                    frame.validate();
                    JTable table = field(app, "table");
                    assertTrue(table.getWidth() >= 900);
                    assertEquals("products", table.getValueAt(0, 1));
                    JTextArea overview = field(app, "overview");
                    assertTrue("The detail pane must remain readable", overview.getParent().getHeight() >= 90);
                    for (String name : List.of("theme", "pause", "clear", "copy", "curl", "search", "applications")) {
                        JComponent control = field(app, name);
                        Rectangle bounds = SwingUtilities.convertRectangle(control.getParent(), control.getBounds(), frame.getContentPane());
                        assertTrue(name + " extends outside the window", bounds.x >= 0 && bounds.x + bounds.width <= frame.getContentPane().getWidth());
                    }
                    Container content = frame.getContentPane();
                    java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(content.getWidth(), content.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = image.createGraphics(); content.printAll(graphics); graphics.dispose();
                    assertNotEquals("The rendered content must not be blank", Color.BLACK.getRGB(), image.getRGB(5, 5));
                    java.nio.file.Path destination = java.nio.file.Path.of("build", "ui-review", mode + ".png");
                    java.nio.file.Files.createDirectories(destination.getParent());
                    javax.imageio.ImageIO.write(image, "png", destination.toFile());
                }
            } catch (Exception error) { throw new RuntimeException(error); }
            finally {
                try { ((ScheduledExecutorService) field(app, "worker")).shutdownNow(); ((JFrame) field(app, "frame")).dispose(); }
                catch (Exception error) { throw new RuntimeException(error); }
            }
        });
    }

    @Test public void endpointNamesKeepFullUrlsSeparate() {
        assertEquals("customer-tier", NetLogDesktop.endpointName("https://example.test/api/customer-tier?page=2"));
        assertEquals("handshake", NetLogDesktop.endpointName("https://example.test/mts/handshake/"));
        assertEquals("example.test", NetLogDesktop.endpointName("https://example.test/?page=2"));
        assertEquals("a%2Fb", NetLogDesktop.endpointName("https://example.test/api/a%2Fb"));
        assertEquals("invalid url", NetLogDesktop.endpointName("invalid url"));
    }

    @Test public void sortsTypedValuesAndKeepsDetailsOnTheSameRequest() throws Exception {
        Assume.assumeFalse("Requires a graphics environment for an invisible JFrame", GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            NetLogDesktop app = new NetLogDesktop();
            try {
                JTable table = field(app, "table");
                JTextArea overview = field(app, "overview");
                JsonObject early = request(1, "GET", "One"), late = request(2, "POST", "Two");
                early.addProperty("time", 9); early.addProperty("durationMs", 100);
                late.addProperty("time", 10); late.addProperty("durationMs", 2);
                early.addProperty("url", "https://example.test/api/zebra");
                late.addProperty("url", "https://example.test/api/alpha");
                ingest(app, early, late);
                RowSorter<?> sorter = table.getRowSorter();
                assertEquals(10L, table.getValueAt(0, 0));
                Rectangle nameHeader = table.getTableHeader().getHeaderRect(1);
                table.getTableHeader().dispatchEvent(new java.awt.event.MouseEvent(table.getTableHeader(),
                    java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, nameHeader.x + 5, 5, 1, false, java.awt.event.MouseEvent.BUTTON1));
                assertEquals("Header clicks must sort the table", 1, sorter.getSortKeys().get(0).getColumn());
                sorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
                assertEquals(9L, table.getValueAt(0, 0));
                sorter.setSortKeys(List.of(new RowSorter.SortKey(4, SortOrder.ASCENDING)));
                assertEquals(2L, table.getValueAt(0, 4));
                sorter.setSortKeys(List.of(new RowSorter.SortKey(1, SortOrder.ASCENDING)));
                assertEquals("alpha", table.getValueAt(0, 1));
                table.setRowSelectionInterval(1, 1);
                assertTrue(overview.getText().contains("/api/zebra"));
                ingest(app, request(3, "PUT", "Three"));
                assertEquals("zebra", table.getValueAt(table.getSelectedRow(), 1));
                assertTrue(overview.getText().contains("/api/zebra"));
                assertEquals(1, sorter.getSortKeys().get(0).getColumn());
                ((JTextField) field(app, "search")).setText("alpha");
                assertEquals(1, table.getRowCount());
                assertTrue(overview.getText().contains("/api/alpha"));
            } catch (Exception error) { throw new RuntimeException(error); }
            finally {
                try { ((ScheduledExecutorService) field(app, "worker")).shutdownNow(); ((JFrame) field(app, "frame")).dispose(); }
                catch (Exception error) { throw new RuntimeException(error); }
            }
        });
    }

    @Test public void filtersWithoutLosingSelectionAndClearIgnoresOldUpdates() throws Exception {
        Assume.assumeFalse("Requires a graphics environment for an invisible JFrame", GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            NetLogDesktop app = new NetLogDesktop();
            try {
                JTable table = field(app, "table"); JTextField search = field(app, "search");
                JComboBox<?> apps = field(app, "applications"); JButton pause = field(app, "pause"), clear = field(app, "clear");
                ingest(app, request(1, "GET", "One"), request(2, "POST", "Two"));
                assertEquals(2, table.getRowCount()); assertEquals("POST", table.getValueAt(table.getSelectedRow(), 2));
                Object previousModel = apps.getModel();
                ingest(app, request(1, "GET", "One"));
                assertSame(previousModel, apps.getModel()); assertEquals("POST", table.getValueAt(table.getSelectedRow(), 2));
                search.setText("get"); assertEquals(1, table.getRowCount()); assertEquals("GET", table.getValueAt(0, 2));
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
