package io.github.kevinmf1.netlog.desktop;

import com.google.gson.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import javax.swing.plaf.basic.*;
import javax.swing.border.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

public final class NetLogDesktop {
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final AdbBridge bridge = new AdbBridge();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "NetLog discovery"); t.setDaemon(true); return t; });
    private final LinkedHashMap<String, JsonObject> records = new LinkedHashMap<>();
    private final Map<String, Long> cleared = new HashMap<>();
    private final List<JsonObject> visible = new ArrayList<>();
    private final JFrame frame = new JFrame("NetLog");
    private final JLabel connection = new JLabel("Looking for emulators and USB devices…");
    private final JLabel summary = new JLabel("Waiting for requests");
    private final JTextField search = new JTextField(24);
    private final JComboBox<String> applications = new JComboBox<>(new String[]{"All apps and devices"});
    private final JButton pause = new JButton("Pause view");
    private final JButton copy = new JButton("Copy detail");
    private final JButton curl = new JButton("Copy cURL");
    private final JButton clear = new JButton("Clear");
    private final JButton theme = new JButton("Dark mode");
    // The device sends real header and query values; they are masked here until the user reveals them.
    private final JToggleButton secrets = new JToggleButton("Show sensitive values");
    static final Set<String> SECRET_HEADERS = Set.of("authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key");
    private final JPanel detailCards = new JPanel(new CardLayout());
    private int selectedDetail;
    private final JTextArea overview = area(), request = area(), response = area();
    // Image responses are previewed by downloading the URL from the desktop, only once the Response view is open.
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final int PREVIEW_LIMIT = 10 * 1024 * 1024;
    private final JLabel preview = new JLabel("", SwingConstants.CENTER);
    private final ExecutorService images = Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task, "NetLog image preview"); thread.setDaemon(true); return thread; });
    // ponytail: last 20 previews kept in memory; a disk cache only if reopening images gets slow.
    private final Map<String, Object> previews = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) { return size() > 20; }
    };
    private String previewUrl = "";
    private final AbstractTableModel model = new AbstractTableModel() {
        final String[] columns = {"Time", "Name", "Method", "Status", "Duration", "URL", "App / device"};
        @Override public int getRowCount() { return visible.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 || column == 4 ? Long.class : String.class; }
        @Override public Object getValueAt(int row, int column) {
            JsonObject value = visible.get(row);
            return switch (column) {
                case 0 -> value.get("time").getAsLong();
                case 1 -> endpointName(text(value, "url"));
                case 2 -> text(value, "method");
                case 3 -> value.has("status") ? text(value, "status") : text(value, "state");
                case 4 -> value.has("durationMs") ? value.get("durationMs").getAsLong() : null;
                case 5 -> shownUrl(text(value, "url"));
                default -> appName(value);
            };
        }
    };
    private final JTable table = new JTable(model);
    // Swing sorts on mouseClicked, which ignores every second quick click and any click with a slight drag.
    // Header clicks are handled on release instead (see sortBy).
    private final TableRowSorter<AbstractTableModel> sorter = new TableRowSorter<>(model) {
        @Override public void toggleSortOrder(int column) { }
    };
    private final JLabel selectedRequest = new JLabel("Request details");
    private Color background, surface, foreground, muted, line, selection, accent;
    private int headerColumn;
    private boolean paused, dark, refreshing;

    private static JTextArea area() {
        JTextArea area = new JTextArea(); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13)); area.setMargin(new Insets(12, 12, 12, 12)); return area;
    }
    static String text(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : ""; }
    static String endpointName(String url) {
        try {
            URI uri = URI.create(url);
            String path = Objects.toString(uri.getRawPath(), "").replaceAll("/+$", "");
            if (!path.isEmpty()) return path.substring(path.lastIndexOf('/') + 1);
            return Objects.toString(uri.getHost(), url);
        } catch (IllegalArgumentException error) { return url; }
    }

    private static String appName(JsonObject record) { return text(record, "app") + " / " + text(record, "device"); }

    NetLogDesktop() {
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(1000, 640)); frame.setSize(1380, 860); frame.setLocationByPlatform(true);
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 12, 20)); frame.setContentPane(root);
        JPanel header = new JPanel(new BorderLayout(12, 0));
        JPanel identity = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        JLabel title = new JLabel("NetLog"); title.setName("title");
        JLabel subtitle = new JLabel("Network inspector"); subtitle.setName("muted");
        identity.add(title); identity.add(subtitle); header.add(identity, BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton locate = new JButton("Locate ADB…");
        actions.add(locate); actions.add(theme); actions.add(pause); actions.add(clear); header.add(actions, BorderLayout.EAST);
        JPanel top = new JPanel(new BorderLayout(0, 14)); top.add(header, BorderLayout.NORTH);
        JPanel filters = new JPanel(new BorderLayout(16, 0));
        JPanel searchPanel = new JPanel(new BorderLayout(10, 0)); JLabel searchLabel = new JLabel("Search"); searchLabel.setLabelFor(search);
        search.getAccessibleContext().setAccessibleName("Search name, URL, method, status, or app");
        search.setToolTipText("Filter requests by name, URL, method, status, or app");
        search.addFocusListener(new FocusAdapter() {
            private void border(boolean focused) { search.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(focused ? accent : line), BorderFactory.createEmptyBorder(7, 10, 7, 10))); }
            @Override public void focusGained(FocusEvent e) { border(true); }
            @Override public void focusLost(FocusEvent e) { border(false); }
        });
        searchPanel.add(searchLabel, BorderLayout.WEST); searchPanel.add(search, BorderLayout.CENTER);
        filters.add(searchPanel, BorderLayout.CENTER);
        JPanel appPanel = new JPanel(new BorderLayout(10, 0));
        JLabel appLabel = new JLabel("Source"); appLabel.setLabelFor(applications);
        applications.setPreferredSize(new Dimension(300, 32));
        applications.getAccessibleContext().setAccessibleName("Filter app and device");
        appPanel.add(appLabel, BorderLayout.WEST); appPanel.add(applications, BorderLayout.CENTER); filters.add(appPanel, BorderLayout.EAST);
        JPanel filterAndStatus = new JPanel(new BorderLayout(0, 10));
        filterAndStatus.add(filters, BorderLayout.NORTH); filterAndStatus.add(connection, BorderLayout.SOUTH);
        connection.setName("muted"); top.add(filterAndStatus, BorderLayout.CENTER); root.add(top, BorderLayout.NORTH);
        frame.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowGainedFocus(WindowEvent e) { if (records.isEmpty()) search.requestFocusInWindow(); }
        });

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setRowHeight(30);
        table.setShowGrid(false); table.setIntercellSpacing(new Dimension(0, 0)); table.setFillsViewportHeight(true);
        table.getAccessibleContext().setAccessibleName("Captured HTTP requests");
        table.setRowSorter(sorter); sorter.setSortsOnUpdates(true);
        sorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.DESCENDING)));
        table.setDefaultRenderer(Object.class, new RequestCell());
        table.setDefaultRenderer(Long.class, new RequestCell());
        int[] widths = {112, 190, 76, 82, 96, 380, 220};
        // Short fixed-format columns keep their content; Name, URL, and App / device can give width to each other.
        int[] minimums = {104, 80, 70, 74, 90, 100, 100};
        for (int i = 0; i < widths.length; i++) {
            TableColumn column = table.getColumnModel().getColumn(i); column.setPreferredWidth(widths[i]); column.setMinWidth(minimums[i]);
        }
        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        table.getTableHeader().setReorderingAllowed(false); table.getTableHeader().setPreferredSize(new Dimension(0, 34));
        table.getTableHeader().setDefaultRenderer(new ColumnHeader());
        table.getTableHeader().setFocusable(true);
        table.getTableHeader().setToolTipText("Click to sort. Drag a column edge to resize. Keyboard: Left / Right to choose a column, Enter to sort.");
        table.getTableHeader().getAccessibleContext().setAccessibleDescription("Use Left or Right to choose a column and Enter or Space to sort.");
        table.getTableHeader().addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_LEFT) headerColumn = Math.max(0, headerColumn - 1);
                else if (e.getKeyCode() == KeyEvent.VK_RIGHT) headerColumn = Math.min(model.getColumnCount() - 1, headerColumn + 1);
                else if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_SPACE) sortBy(headerColumn);
                else return;
                e.consume(); table.getTableHeader().repaint();
            }
        });
        table.getTableHeader().addMouseListener(new MouseAdapter() {
            private Point pressed; private int column = -1;
            // Runs after the header UI's own listener, so a press on a column edge is already marked as a resize.
            @Override public void mousePressed(MouseEvent e) {
                pressed = e.getPoint();
                column = SwingUtilities.isLeftMouseButton(e) && table.getTableHeader().getResizingColumn() == null ? table.getTableHeader().columnAtPoint(pressed) : -1;
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (column >= 0 && column == table.getTableHeader().columnAtPoint(e.getPoint()) && pressed.distance(e.getPoint()) <= 6) sortBy(column);
                column = -1;
            }
        });
        table.getTableHeader().addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { table.getTableHeader().repaint(); }
            @Override public void focusLost(FocusEvent e) { table.getTableHeader().repaint(); }
        });
        JScrollPane list = new JScrollPane(table);
        JPanel details = new JPanel(new BorderLayout(0, 8));
        JPanel detailHeader = new JPanel(new BorderLayout(16, 0));
        JPanel detailNavigation = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        ButtonGroup detailGroup = new ButtonGroup();
        String[] views = {"Overview", "Request", "Response"};
        JTextArea[] bodies = {overview, request, response};
        for (int i = 0; i < views.length; i++) {
            final int index = i; String view = views[i];
            JToggleButton button = new JToggleButton(view, i == 0);
            button.addActionListener(e -> { selectedDetail = index; ((CardLayout) detailCards.getLayout()).show(detailCards, view); if (index == 2) loadPreview(selected()); });
            detailGroup.add(button); detailNavigation.add(button);
            detailCards.add(new JScrollPane(i == 2 ? new PreviewAbove(preview, response) : bodies[i]), view);
        }
        preview.setVisible(false); preview.getAccessibleContext().setAccessibleName("Image preview");
        overview.getAccessibleContext().setAccessibleName("Request overview"); request.getAccessibleContext().setAccessibleName("Request headers and body"); response.getAccessibleContext().setAccessibleName("Response headers and body");
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0)); tools.add(secrets); tools.add(copy); tools.add(curl);
        detailHeader.add(detailNavigation, BorderLayout.WEST); detailHeader.add(tools, BorderLayout.EAST);
        JPanel detailTop = new JPanel(new BorderLayout(0, 8)); selectedRequest.setName("endpoint");
        detailTop.add(detailHeader, BorderLayout.NORTH); detailTop.add(selectedRequest, BorderLayout.SOUTH);
        details.add(detailTop, BorderLayout.NORTH); details.add(detailCards, BorderLayout.CENTER);
        list.setMinimumSize(new Dimension(0, 100)); details.setMinimumSize(new Dimension(0, 180));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, list, details) {
            @Override public void doLayout() {
                int maximum = getHeight() - getDividerSize() - details.getMinimumSize().height;
                if (maximum >= 100 && getDividerLocation() > maximum) setDividerLocation(maximum);
                super.doLayout();
            }
        };
        split.setResizeWeight(0.62); split.setDividerLocation(410); split.setDividerSize(12); split.setContinuousLayout(true);
        root.add(split, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(12, 0));
        summary.setName("muted"); JLabel sortHint = new JLabel("Click column headers to sort"); sortHint.setName("muted");
        footer.add(summary, BorderLayout.CENTER); footer.add(sortHint, BorderLayout.EAST); root.add(footer, BorderLayout.SOUTH);
        table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && !refreshing) showDetail(); });
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refresh(); } public void removeUpdate(DocumentEvent e) { refresh(); } public void changedUpdate(DocumentEvent e) { refresh(); }
        });
        applications.addActionListener(e -> { if (!refreshing) refresh(); });
        pause.addActionListener(e -> { paused = !paused; pause.setText(paused ? "Resume view" : "Pause view"); if (!paused) refresh(); else summary.setText("View paused. Capture continues; resume to show the latest requests."); });
        clear.addActionListener(e -> {
            records.values().forEach(r -> cleared.merge(text(r, "device") + "/" + text(r, "session"), r.get("id").getAsLong(), Math::max));
            records.clear(); refresh();
        });
        copy.addActionListener(e -> clipboard(switch(selectedDetail) { case 1 -> request.getText(); case 2 -> response.getText(); default -> overview.getText(); }));
        curl.addActionListener(e -> { JsonObject selected = selected(); if (selected != null) clipboard(curl(shown(selected))); });
        secrets.getAccessibleContext().setAccessibleDescription("Shows or hides authorization, cookie, and API key values in the view and in copies.");
        secrets.addActionListener(e -> { secrets.setText(secrets.isSelected() ? "Hide sensitive values" : "Show sensitive values"); table.repaint(); showDetail(); });
        theme.addActionListener(e -> { dark = !dark; applyTheme(); });
        locate.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(); chooser.setDialogTitle("Select Android SDK platform-tools/adb");
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                String path = chooser.getSelectedFile().getAbsolutePath();
                connection.setText("Connecting with selected ADB…"); worker.execute(() -> bridge.setExecutable(path));
            }
        });
        frame.getRootPane().registerKeyboardAction(e -> search.requestFocusInWindow(), KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), JComponent.WHEN_IN_FOCUSED_WINDOW);
        frame.addWindowListener(new WindowAdapter() { @Override public void windowClosed(WindowEvent e) { worker.shutdownNow(); new Thread(bridge::close, "NetLog cleanup").start(); } });
        applyTheme(); showDetail();
    }

    private void clipboard(String value) { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null); summary.setText(secrets.isSelected() ? "Copied to clipboard, including sensitive values." : "Copied to clipboard. Sensitive values are hidden."); }

    private void ingest(AdbBridge.Batch batch) {
        connection.setText(batch.status()); connection.setToolTipText(batch.status());
        for (JsonObject record : batch.records()) {
            String session = text(record, "device") + "/" + text(record, "session");
            if (record.get("id").getAsLong() > cleared.getOrDefault(session, 0L)) records.put(text(record, "key"), record);
        }
        long size = records.values().stream().mapToLong(r -> r.toString().length()).sum();
        while (records.size() > 1000 || size > 8 * 1024 * 1024) {
            String first = records.keySet().iterator().next(); size -= records.remove(first).toString().length();
        }
        if (!paused && !batch.records().isEmpty()) refresh();
    }

    private JsonObject selected() {
        int row = table.getSelectedRow();
        return row >= 0 && row < table.getRowCount() ? visible.get(table.convertRowIndexToModel(row)) : null;
    }

    private void refresh() {
        JsonObject selected = selected(); String key = selected == null ? "" : text(selected, "key");
        String filter = Objects.toString(applications.getSelectedItem(), "All apps and devices");
        String query = search.getText().toLowerCase(Locale.ROOT);
        refreshing = true;
        TreeSet<String> names = new TreeSet<>(); records.values().forEach(r -> names.add(appName(r)));
        List<String> choices = new ArrayList<>(); choices.add("All apps and devices"); choices.addAll(names);
        if (!choices.contains(filter)) filter = choices.get(0);
        List<String> current = new ArrayList<>();
        for (int i = 0; i < applications.getItemCount(); i++) current.add(applications.getItemAt(i));
        if (!current.equals(choices)) applications.setModel(new DefaultComboBoxModel<>(choices.toArray(String[]::new)));
        applications.setSelectedItem(filter);
        visible.clear();
        for (JsonObject record : records.values()) {
            String haystack = text(record, "url") + " " + text(record, "method") + " " + text(record, "status") + " " + appName(record);
            if ((filter.equals("All apps and devices") || appName(record).equals(filter)) && haystack.toLowerCase(Locale.ROOT).contains(query)) visible.add(record);
        }
        model.fireTableDataChanged();
        table.clearSelection();
        for (int i = 0; i < visible.size(); i++) if (text(visible.get(i), "key").equals(key)) {
            int view = table.convertRowIndexToView(i); table.setRowSelectionInterval(view, view); break;
        }
        if (table.getSelectedRow() < 0 && !visible.isEmpty()) table.setRowSelectionInterval(0, 0);
        refreshing = false;
        summary.setText(visible.isEmpty() ? (records.isEmpty() ? "No requests yet. Use your Android app; traffic appears here automatically." : "No matching requests. Change the search or app filter.")
            : visible.size() + " requests shown • " + records.size() + " retained • bodies up to 64 KiB • memory only");
        showDetail();
    }

    private void showDetail() {
        JsonObject value = selected(); copy.setEnabled(value != null); curl.setEnabled(value != null);
        selectedRequest.setText(value == null ? "Request details" : text(value, "method") + "  " + endpointName(text(value, "url")));
        selectedRequest.setToolTipText(value == null ? null : shownUrl(text(value, "url")));
        if (selectedDetail == 2) loadPreview(value);
        if (value == null) { overview.setText("Select a request to inspect its timing, headers, and body."); request.setText(""); response.setText(""); return; }
        value = shown(value);
        String info = text(value, "method") + " " + text(value, "url") + "\n\n"
            + "Status: " + text(value, "status") + "  " + text(value, "state") + "\n"
            + "App: " + text(value, "app") + " (" + text(value, "package") + ")\nDevice: " + text(value, "device")
            + "\nTime to headers: " + text(value, "headersMs") + " ms\nDuration through body close: " + text(value, "durationMs") + " ms"
            + "\nResponse bytes consumed: " + text(value, "responseBytes") + "\nFinal URL: " + text(value, "finalUrl");
        if (value.has("error")) info += "\nError: " + text(value, "error");
        updateText(overview, info); updateText(request, detail(value, "request")); updateText(response, detail(value, "response"));
    }

    private void loadPreview(JsonObject value) {
        String url = value == null || !isImage(value) ? "" : text(value, "url");
        if (url.equals(previewUrl)) return;
        previewUrl = url; preview.setIcon(null); preview.setToolTipText(null); preview.setVisible(!url.isEmpty());
        if (url.isEmpty()) return;
        Object cached = previews.get(url);
        if (cached != null) { showPreview(cached); return; }
        String host;
        try { host = Objects.toString(URI.create(url).getHost(), ""); }
        catch (IllegalArgumentException error) { showPreview("Image preview unavailable: invalid URL."); return; }
        if (host.equals("localhost") || host.startsWith("127.") || host.equals("[::1]") || host.equals("10.0.2.2")) {
            showPreview("Image preview unavailable: this address is local to the device, so the desktop can't reach it."); return;
        }
        preview.setText("Loading image preview…");
        images.execute(() -> {
            Object result = fetchImage(url, value.has("requestHeaders") ? value.getAsJsonArray("requestHeaders") : new JsonArray());
            SwingUtilities.invokeLater(() -> { previews.put(url, result); if (url.equals(previewUrl)) showPreview(result); });
        });
    }

    private void showPreview(Object result) {
        if (result instanceof java.awt.image.BufferedImage image) {
            double scale = Math.min(1, Math.min(480.0 / image.getWidth(), 240.0 / image.getHeight()));
            preview.setText(null);
            preview.setIcon(new ImageIcon(image.getScaledInstance(Math.max(1, (int) (image.getWidth() * scale)), Math.max(1, (int) (image.getHeight() * scale)), Image.SCALE_SMOOTH)));
            preview.setToolTipText(image.getWidth() + " × " + image.getHeight() + " px, downloaded by the desktop");
        } else { preview.setIcon(null); preview.setText(result.toString()); }
    }

    static boolean isImage(JsonObject record) {
        if (!text(record, "method").equals("GET") || !text(record, "status").startsWith("2")) return false;
        String path = text(record, "url").toLowerCase(Locale.ROOT).replaceAll("[?#].*", "");
        return contentType(record).startsWith("image/") || path.matches(".*\\.(png|jpe?g|gif|bmp|webp)");
    }

    static String contentType(JsonObject record) {
        if (record.has("responseHeaders")) for (JsonElement element : record.getAsJsonArray("responseHeaders")) {
            JsonArray pair = element.getAsJsonArray();
            if (pair.get(0).getAsString().equalsIgnoreCase("Content-Type")) return pair.get(1).getAsString().toLowerCase(Locale.ROOT);
        }
        return "";
    }

    // Transport headers are left to the desktop's HTTP client; encoded bodies would not decode as images.
    private static final Set<String> NOT_FORWARDED = Set.of("host", "connection", "content-length", "expect", "upgrade", "accept-encoding", "te", "keep-alive", "transfer-encoding");

    /** Returns a BufferedImage, or a message explaining why there is no preview. Sends the app's own request headers. */
    static Object fetchImage(String url, JsonArray headers) {
        try {
            URI uri = URI.create(url);
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) return "Image preview needs an http or https URL.";
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).setHeader("User-Agent", "NetLog desktop preview");
            for (JsonElement element : headers) {
                JsonArray pair = element.getAsJsonArray(); String name = pair.get(0).getAsString(), value = pair.get(1).getAsString();
                if (NOT_FORWARDED.contains(name.toLowerCase(Locale.ROOT)) || value.equals("[REDACTED]")) continue;
                try { builder.setHeader(name, value); } catch (IllegalArgumentException restricted) { }
            }
            HttpRequest request = builder.build();
            HttpResponse<java.io.InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (java.io.InputStream body = response.body()) {
                int status = response.statusCode();
                if (status / 100 != 2) return "Image preview unavailable: the server returned " + status + " to the desktop"
                    + (status == 401 || status == 403 ? ", even with the app's request headers." : ".");
                byte[] bytes = body.readNBytes(PREVIEW_LIMIT + 1);
                if (bytes.length > PREVIEW_LIMIT) return "Image preview skipped: the image is larger than 10 MiB.";
                java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                return image != null ? image : "Image preview unavailable for this format (" + response.headers().firstValue("Content-Type").orElse("unknown") + "). PNG, JPEG, GIF, and BMP can be shown.";
            }
        } catch (IllegalArgumentException error) {
            return "Image preview unavailable: invalid URL.";
        } catch (java.io.IOException error) {
            return "Image preview unavailable: the desktop couldn't download it (" + error.getClass().getSimpleName() + ").";
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); return "Image preview was interrupted.";
        }
    }

    private JsonObject shown(JsonObject record) { return secrets.isSelected() ? record : masked(record); }
    private String shownUrl(String url) { return secrets.isSelected() ? url : maskUrl(url); }

    static JsonObject masked(JsonObject record) {
        JsonObject copy = record.deepCopy();
        for (String key : List.of("url", "finalUrl")) if (copy.has(key)) copy.addProperty(key, maskUrl(text(copy, key)));
        for (String side : List.of("requestHeaders", "responseHeaders")) if (copy.has(side)) for (JsonElement element : copy.getAsJsonArray(side)) {
            JsonArray pair = element.getAsJsonArray();
            if (SECRET_HEADERS.contains(pair.get(0).getAsString().toLowerCase(Locale.ROOT))) pair.set(1, new JsonPrimitive("[REDACTED]"));
        }
        return copy;
    }

    static String maskUrl(String url) {
        String result = url.replaceFirst("^([A-Za-z][A-Za-z0-9+.-]*://)[^/?#@]*@", "$1[REDACTED]@");
        int query = result.indexOf('?');
        if (query < 0) return result;
        int fragment = result.indexOf('#', query); if (fragment < 0) fragment = result.length();
        StringJoiner parts = new StringJoiner("&");
        for (String part : result.substring(query + 1, fragment).split("&", -1)) {
            int equals = part.indexOf('=');
            String name = (equals < 0 ? part : part.substring(0, equals)).toLowerCase(Locale.ROOT);
            boolean secret = name.contains("token") || name.contains("secret") || name.contains("password") || name.equals("api_key") || name.equals("apikey");
            parts.add(secret && equals >= 0 ? part.substring(0, equals + 1) + "[REDACTED]" : part);
        }
        return result.substring(0, query + 1) + parts + result.substring(fragment);
    }

    private static void updateText(JTextArea area, String value) { if (!area.getText().equals(value)) { int caret = area.getCaretPosition(); area.setText(value); area.setCaretPosition(Math.min(caret, value.length())); } }

    static String detail(JsonObject record, String side) {
        StringBuilder value = new StringBuilder();
        if (record.has(side + "Headers")) for (JsonElement element : record.getAsJsonArray(side + "Headers")) {
            JsonArray pair = element.getAsJsonArray(); value.append(pair.get(0).getAsString()).append(": ").append(pair.get(1).getAsString()).append('\n');
        }
        String body = text(record, side + "Body");
        try { if (!body.isEmpty()) body = PRETTY.toJson(JsonParser.parseString(body)); } catch (JsonParseException ignored) { }
        value.append("\n").append(body.isEmpty() ? "[No captured body yet]" : body);
        if (record.has(side + "Truncated") && record.get(side + "Truncated").getAsBoolean()) value.append("\n\n[Truncated at 64 KiB]");
        return value.toString();
    }

    static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
    static String curl(JsonObject record) {
        StringBuilder value = new StringBuilder("curl --request ").append(quote(text(record, "method"))).append(" --url ").append(quote(text(record, "url")));
        if (record.has("requestHeaders")) for (JsonElement element : record.getAsJsonArray("requestHeaders")) {
            JsonArray pair = element.getAsJsonArray(); String name = pair.get(0).getAsString();
            if (!name.equalsIgnoreCase("Content-Length")) value.append(" \\\n  --header ").append(quote(name + ": " + pair.get(1).getAsString()));
        }
        String body = text(record, "requestBody");
        if (!body.isEmpty() && !body.startsWith("[Body omitted")) value.append(" \\\n  --data-raw ").append(quote(body));
        if (record.has("requestTruncated") && record.get("requestTruncated").getAsBoolean()) value.append("\n# Body was truncated; replace it before replaying.");
        return value.toString();
    }

    private void applyTheme() {
        background = new Color(dark ? 0x171B22 : 0xF5F7FA);
        surface = new Color(dark ? 0x20252E : 0xFFFFFF);
        foreground = new Color(dark ? 0xE7ECF3 : 0x1F2937);
        muted = new Color(dark ? 0xAAB6C6 : 0x596579);
        line = new Color(dark ? 0x526174 : 0xC2CAD5);
        selection = new Color(dark ? 0x283E60 : 0xE1EBFA);
        accent = new Color(dark ? 0x98BCFF : 0x245BB0);
        paint(frame.getContentPane(), background, surface, foreground);
        table.setSelectionBackground(selection); table.setSelectionForeground(foreground);
        table.getTableHeader().setBackground(background); table.getTableHeader().setForeground(muted);
        for (JTextArea area : List.of(overview, request, response)) {
            area.setCaretColor(foreground); area.setSelectionColor(selection); area.setSelectedTextColor(foreground);
        }
        search.setCaretColor(foreground); search.setSelectionColor(selection); search.setSelectedTextColor(foreground);
        search.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(search.hasFocus() ? accent : line), BorderFactory.createEmptyBorder(7, 10, 7, 10)));
        theme.setText(dark ? "Light mode" : "Dark mode"); frame.repaint();
    }
    private void paint(Component component, Color bg, Color panel, Color fg) {
        component.setForeground(fg); component.setBackground(bg);
        if (!(component instanceof JTextArea)) component.setFont(new Font(Font.DIALOG, Font.PLAIN, 13));
        if (component instanceof JLabel label) {
            label.putClientProperty("html.disable", true);
            if ("muted".equals(label.getName())) { label.setForeground(muted); label.setFont(label.getFont().deriveFont(12f)); }
            if ("title".equals(label.getName())) label.setFont(label.getFont().deriveFont(Font.BOLD, 21f));
            if ("endpoint".equals(label.getName())) label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        }
        if (component instanceof JTextArea || component instanceof JTextField || component instanceof JTable || component instanceof JViewport) component.setBackground(panel);
        if (component instanceof JScrollPane scroll) scroll.setBorder(BorderFactory.createLineBorder(line));
        if (component instanceof JSplitPane split) {
            split.setUI(new BasicSplitPaneUI() {
                @Override public BasicSplitPaneDivider createDefaultDivider() {
                    return new BasicSplitPaneDivider(this) {
                        @Override public void paint(Graphics g) {
                            g.setColor(background); g.fillRect(0, 0, getWidth(), getHeight());
                            g.setColor(line); g.fillRoundRect(getWidth() / 2 - 18, getHeight() / 2, 36, 2, 2, 2);
                        }
                    };
                }
            });
            split.setBorder(BorderFactory.createEmptyBorder());
        }
        if (component instanceof JScrollBar bar) {
            bar.setUI(new BasicScrollBarUI() {
                @Override protected JButton createDecreaseButton(int direction) { return arrowless(); }
                @Override protected JButton createIncreaseButton(int direction) { return arrowless(); }
                private JButton arrowless() { JButton b = new JButton(); b.setPreferredSize(new Dimension(0, 0)); return b; }
                @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) { g.setColor(surface); g.fillRect(r.x, r.y, r.width, r.height); }
                @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
                    Graphics2D copy = (Graphics2D) g.create();
                    copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    copy.setColor(line); copy.fillRoundRect(r.x + 3, r.y + 3, Math.max(2, r.width - 6), Math.max(2, r.height - 6), 6, 6);
                    copy.dispose();
                }
            });
            bar.setPreferredSize(new Dimension(12, 12));
        }
        if (component instanceof JComboBox<?> combo) {
            combo.setUI(new BasicComboBoxUI() {
                @Override public void installUI(JComponent c) {
                    super.installUI(c); listBox.setBackground(surface); listBox.setForeground(foreground);
                    listBox.setSelectionBackground(selection); listBox.setSelectionForeground(foreground);
                }
                // Basic paints the LAF's light ComboBox.background here, and the renderer is transparent when it matches the combo.
                @Override public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
                    g.setColor(comboBox.getBackground()); g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
                }
                @Override protected JButton createArrowButton() {
                    JButton arrow = new JButton("▾"); arrow.setName("comboArrow"); return arrow;
                }
            });
            combo.setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                    super.getListCellRendererComponent(list, value, index, selected, focus);
                    putClientProperty("html.disable", true); setFont(new Font(Font.DIALOG, Font.PLAIN, 13));
                    setBackground(selected ? selection : surface); setForeground(foreground);
                    setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8)); return this;
                }
            });
            combo.setBackground(panel); combo.setBorder(BorderFactory.createLineBorder(line));
        }
        if (component instanceof AbstractButton button) {
            button.setUI(new BasicButtonUI() {
                @Override public void paint(Graphics graphics, JComponent c) {
                    Graphics2D g = (Graphics2D) graphics.create();
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    boolean active = button.isSelected() || button.getModel().isPressed();
                    g.setColor(active || button.getModel().isRollover() ? selection : surface);
                    g.fillRoundRect(0, 0, c.getWidth() - 1, c.getHeight() - 1, 8, 8);
                    g.setColor(button.hasFocus() || button.isSelected() ? accent : line);
                    g.setStroke(new BasicStroke(button.hasFocus() ? 2 : 1));
                    g.drawRoundRect(1, 1, c.getWidth() - 3, c.getHeight() - 3, 8, 8);
                    g.dispose(); super.paint(graphics, c);
                }
            });
            button.setOpaque(false); button.setContentAreaFilled(false); button.setRolloverEnabled(true);
            button.setBorder("comboArrow".equals(button.getName()) ? BorderFactory.createEmptyBorder(4, 4, 4, 4) : BorderFactory.createEmptyBorder(7, 12, 7, 12));
        }
        if (component instanceof Container container) for (Component child : container.getComponents()) paint(child, bg, panel, fg);
    }

    private final class RequestCell extends DefaultTableCellRenderer {
        RequestCell() { putClientProperty("html.disable", true); }
        @Override public Component getTableCellRendererComponent(JTable owner, Object value, boolean selected, boolean focus, int row, int column) {
            int modelColumn = owner.convertColumnIndexToModel(column);
            Object formatted = value;
            if (modelColumn == 0 && value instanceof Long time) formatted = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date(time));
            if (modelColumn == 4) formatted = value == null ? "Pending" : value + " ms";
            super.getTableCellRendererComponent(owner, formatted, selected, focus, row, column);
            setFont(owner.getFont().deriveFont(modelColumn == 1 || modelColumn == 2 ? Font.BOLD : Font.PLAIN));
            setHorizontalAlignment(modelColumn == 3 || modelColumn == 4 ? SwingConstants.RIGHT : SwingConstants.LEFT);
            setBackground(selected ? selection : row % 2 == 0 ? surface : new Color(dark ? 0x242A34 : 0xFAFBFD));
            setForeground(modelColumn == 0 || modelColumn >= 5 ? muted : foreground);
            if (modelColumn == 3) {
                try {
                    int status = Integer.parseInt(Objects.toString(value, ""));
                    setForeground(status >= 400 ? new Color(dark ? 0xFFACB2 : 0xAD2831) : status >= 200 && status < 300 ? new Color(dark ? 0x8CD6B0 : 0x176641) : foreground);
                } catch (NumberFormatException ignored) { setForeground(muted); }
            }
            setBorder(BorderFactory.createCompoundBorder(
                focus ? BorderFactory.createLineBorder(accent) : BorderFactory.createEmptyBorder(1, 1, 1, 1),
                BorderFactory.createEmptyBorder(0, 9, 0, 9)));
            JsonObject record = visible.get(owner.convertRowIndexToModel(row));
            setToolTipText(modelColumn == 1 || modelColumn == 5 ? shownUrl(text(record, "url")) : Objects.toString(formatted, ""));
            return this;
        }
    }

    /** Image preview and response text scroll together; the text still wraps to the viewport width. */
    private static final class PreviewAbove extends JPanel implements Scrollable {
        PreviewAbove(JComponent preview, JComponent text) {
            super(new BorderLayout(0, 8)); setOpaque(false);
            preview.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
            add(preview, BorderLayout.NORTH); add(text, BorderLayout.CENTER);
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return visible.height; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return getParent() instanceof JViewport viewport && viewport.getHeight() > getPreferredSize().height; }
    }

    private final class ColumnHeader extends DefaultTableCellRenderer {
        ColumnHeader() { putClientProperty("html.disable", true); }
        @Override public Component getTableCellRendererComponent(JTable owner, Object value, boolean selected, boolean focus, int row, int column) {
            super.getTableCellRendererComponent(owner, value, selected, focus, row, column);
            String arrow = "";
            if (!sorter.getSortKeys().isEmpty()) {
                RowSorter.SortKey key = sorter.getSortKeys().get(0);
                if (key.getColumn() == column) arrow = key.getSortOrder() == SortOrder.ASCENDING ? "  ▲" : "  ▼";
            }
            setText(value + arrow); setFont(owner.getFont().deriveFont(Font.BOLD, 12f));
            setBackground(background); setForeground(arrow.isEmpty() ? muted : accent);
            setHorizontalAlignment(column == 3 || column == 4 ? SwingConstants.RIGHT : SwingConstants.LEFT);
            boolean keyboardFocus = owner.getTableHeader().hasFocus() && headerColumn == column;
            int separator = column < owner.getColumnCount() - 1 ? 1 : 0; // marks the edge that can be dragged to resize
            setBorder(BorderFactory.createCompoundBorder(
                keyboardFocus ? BorderFactory.createLineBorder(accent, 2) : BorderFactory.createMatteBorder(0, 0, 1, separator, line),
                BorderFactory.createEmptyBorder(0, 10, 0, 10)));
            return this;
        }
    }

    private void sortBy(int viewColumn) {
        int column = table.convertColumnIndexToModel(viewColumn);
        List<? extends RowSorter.SortKey> keys = sorter.getSortKeys();
        boolean ascending = !keys.isEmpty() && keys.get(0).getColumn() == column && keys.get(0).getSortOrder() == SortOrder.ASCENDING;
        sorter.setSortKeys(List.of(new RowSorter.SortKey(column, ascending ? SortOrder.DESCENDING : SortOrder.ASCENDING)));
        table.getTableHeader().repaint();
    }

    private void start() {
        frame.setVisible(true);
        worker.scheduleWithFixedDelay(() -> {
            try { AdbBridge.Batch batch = bridge.poll(); SwingUtilities.invokeLater(() -> ingest(batch)); }
            catch (Exception error) { SwingUtilities.invokeLater(() -> connection.setText("Reconnecting: " + error.getClass().getSimpleName())); }
        }, 0, 1, TimeUnit.SECONDS);
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--probe")) {
            try (AdbBridge bridge = new AdbBridge()) {
                AdbBridge.Batch batch = bridge.poll(); System.out.println(batch.status());
                batch.records().forEach(r -> System.out.println(r.toString()));
            }
            return;
        }
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        SwingUtilities.invokeLater(() -> new NetLogDesktop().start());
    }
}
