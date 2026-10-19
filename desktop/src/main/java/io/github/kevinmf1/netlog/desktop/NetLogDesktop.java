package io.github.kevinmf1.netlog.desktop;

import com.google.gson.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.nio.file.*;
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
    private final JPanel detailCards = new JPanel(new CardLayout());
    private int selectedDetail;
    private final JTextArea overview = area(), request = area(), response = area();
    private final AbstractTableModel model = new AbstractTableModel() {
        final String[] columns = {"Time", "Method", "Status", "URL", "Duration", "App / device"};
        @Override public int getRowCount() { return visible.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int row, int column) {
            JsonObject value = visible.get(row);
            return switch (column) {
                case 0 -> new SimpleDateFormat("HH:mm:ss").format(new Date(value.get("time").getAsLong()));
                case 1 -> text(value, "method");
                case 2 -> value.has("status") ? text(value, "status") : text(value, "state");
                case 3 -> text(value, "url");
                case 4 -> value.has("durationMs") ? text(value, "durationMs") + " ms" : "…";
                default -> appName(value);
            };
        }
    };
    private final JTable table = new JTable(model);
    private boolean paused, dark, refreshing;

    private static JTextArea area() {
        JTextArea area = new JTextArea(); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13)); area.setMargin(new Insets(12, 12, 12, 12)); return area;
    }
    static String text(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : ""; }
    private static String appName(JsonObject record) { return text(record, "app") + " / " + text(record, "device"); }

    NetLogDesktop() {
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(900, 620)); frame.setSize(1280, 800); frame.setLocationByPlatform(true);
        JPanel root = new JPanel(new BorderLayout(0, 12)); root.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20)); frame.setContentPane(root);
        JPanel header = new JPanel(new BorderLayout(12, 12));
        JLabel title = new JLabel("NetLog"); title.setFont(title.getFont().deriveFont(Font.BOLD, 24)); header.add(title, BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton locate = new JButton("Locate ADB…");
        actions.add(locate); actions.add(theme); actions.add(pause); actions.add(clear); header.add(actions, BorderLayout.EAST);
        JPanel top = new JPanel(new BorderLayout(0, 14)); top.add(header, BorderLayout.NORTH);
        JPanel filters = new JPanel(new BorderLayout(12, 8));
        JPanel searchPanel = new JPanel(new BorderLayout(8, 0)); JLabel searchLabel = new JLabel("Search"); searchLabel.setLabelFor(search);
        search.getAccessibleContext().setAccessibleName("Search URL, method, status, or app");
        frame.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowGainedFocus(WindowEvent e) { if (records.isEmpty()) search.requestFocusInWindow(); }
        });
        searchPanel.add(searchLabel, BorderLayout.WEST); searchPanel.add(search, BorderLayout.CENTER);
        filters.add(searchPanel, BorderLayout.CENTER); filters.add(applications, BorderLayout.EAST);
        applications.getAccessibleContext().setAccessibleName("Filter app and device");
        top.add(filters, BorderLayout.CENTER); top.add(connection, BorderLayout.SOUTH); root.add(top, BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setRowHeight(30); table.setShowGrid(false);
        table.getAccessibleContext().setAccessibleName("Captured HTTP requests");
        table.getColumnModel().getColumn(3).setPreferredWidth(420);
        table.getColumnModel().getColumn(5).setPreferredWidth(200);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        JScrollPane list = new JScrollPane(table);
        JPanel detailNavigation = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        ButtonGroup detailGroup = new ButtonGroup();
        String[] views = {"Overview", "Request", "Response"};
        JTextArea[] bodies = {overview, request, response};
        for (int i = 0; i < views.length; i++) {
            final int index = i;
            String view = views[i];
            JToggleButton button = new JToggleButton(view, i == 0);
            button.addActionListener(e -> { selectedDetail = index; ((CardLayout) detailCards.getLayout()).show(detailCards, view); });
            detailGroup.add(button); detailNavigation.add(button);
            detailCards.add(new JScrollPane(bodies[i]), view);
        }
        overview.getAccessibleContext().setAccessibleName("Request overview"); request.getAccessibleContext().setAccessibleName("Request headers and body"); response.getAccessibleContext().setAccessibleName("Response headers and body");
        JPanel details = new JPanel(new BorderLayout()); details.add(detailNavigation, BorderLayout.NORTH); details.add(detailCards, BorderLayout.CENTER);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT)); tools.add(copy); tools.add(curl); details.add(tools, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, list, details); split.setResizeWeight(0.55); split.setDividerLocation(310);
        root.add(split, BorderLayout.CENTER); root.add(summary, BorderLayout.SOUTH);
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
        curl.addActionListener(e -> { JsonObject selected = selected(); if (selected != null) clipboard(curl(selected)); });
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

    private void clipboard(String value) { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null); summary.setText("Copied to clipboard. Sensitive header values remain redacted."); }

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

    private JsonObject selected() { int row = table.getSelectedRow(); return row >= 0 && row < visible.size() ? visible.get(row) : null; }

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
        visible.sort(Comparator.comparingLong((JsonObject r) -> r.get("time").getAsLong()).reversed());
        model.fireTableDataChanged();
        for (int i = 0; i < visible.size(); i++) if (text(visible.get(i), "key").equals(key)) { table.setRowSelectionInterval(i, i); break; }
        if (table.getSelectedRow() < 0 && !visible.isEmpty()) table.setRowSelectionInterval(0, 0);
        refreshing = false;
        summary.setText(visible.isEmpty() ? (records.isEmpty() ? "No requests yet. Use your Android app; traffic appears here automatically." : "No matching requests. Change the search or app filter.")
            : visible.size() + " requests shown • " + records.size() + " retained • bodies up to 64 KiB • memory only");
        showDetail();
    }

    private void showDetail() {
        JsonObject value = selected(); copy.setEnabled(value != null); curl.setEnabled(value != null);
        if (value == null) { overview.setText("Select a request to inspect its timing, headers, and body."); request.setText(""); response.setText(""); return; }
        String info = text(value, "method") + " " + text(value, "url") + "\n\n"
            + "Status: " + text(value, "status") + "  " + text(value, "state") + "\n"
            + "App: " + text(value, "app") + " (" + text(value, "package") + ")\nDevice: " + text(value, "device")
            + "\nTime to headers: " + text(value, "headersMs") + " ms\nDuration through body close: " + text(value, "durationMs") + " ms"
            + "\nResponse bytes consumed: " + text(value, "responseBytes") + "\nFinal URL: " + text(value, "finalUrl");
        if (value.has("error")) info += "\nError: " + text(value, "error");
        updateText(overview, info); updateText(request, detail(value, "request")); updateText(response, detail(value, "response"));
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
        Color bg = new Color(dark ? 0x18201E : 0xF1F4F2), surface = new Color(dark ? 0x202B27 : 0xFFFFFF);
        Color fg = new Color(dark ? 0xECF3EF : 0x1D3027), accent = new Color(dark ? 0x80D5AD : 0x165B3D);
        UIManager.put("ToggleButton.select", surface);
        UIManager.put("ToggleButton.focus", fg);
        paint(frame.getContentPane(), bg, surface, fg);
        table.setSelectionBackground(accent); table.setSelectionForeground(new Color(dark ? 0x10251B : 0xFFFFFF));
        table.getTableHeader().setBackground(bg); table.getTableHeader().setForeground(fg);
        for (JTextArea area : List.of(overview, request, response)) { area.setCaretColor(fg); area.setSelectionColor(accent); area.setSelectedTextColor(new Color(dark ? 0x10251B : 0xFFFFFF)); }
        search.setCaretColor(fg); theme.setText(dark ? "Light mode" : "Dark mode"); frame.repaint();
    }
    private static void paint(Component component, Color bg, Color surface, Color fg) {
        if (component instanceof JToggleButton toggle) toggle.updateUI();
        component.setForeground(fg);
        component.setBackground(component instanceof JTextArea || component instanceof JTextField || component instanceof JTable ? surface : bg);
        if (component instanceof Container container) for (Component child : container.getComponents()) paint(child, bg, surface, fg);
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
