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
    private final JPanel detailCards = new JPanel(new CardLayout());
    private int selectedDetail;
    private final JTextArea overview = area(), request = area(), response = area();
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
                case 5 -> text(value, "url");
                default -> appName(value);
            };
        }
    };
    private final JTable table = new JTable(model);
    private final TableRowSorter<AbstractTableModel> sorter = new TableRowSorter<>(model);
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
        int[] minimums = {104, 130, 70, 74, 90, 210, 180};
        for (int i = 0; i < widths.length; i++) {
            TableColumn column = table.getColumnModel().getColumn(i); column.setPreferredWidth(widths[i]); column.setMinWidth(minimums[i]);
        }
        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        table.getTableHeader().setReorderingAllowed(false); table.getTableHeader().setPreferredSize(new Dimension(0, 34));
        table.getTableHeader().setDefaultRenderer(new ColumnHeader());
        table.getTableHeader().setFocusable(true);
        table.getTableHeader().setToolTipText("Click to sort. Keyboard: Left / Right to choose a column, Enter to sort.");
        table.getTableHeader().getAccessibleContext().setAccessibleDescription("Use Left or Right to choose a column and Enter or Space to sort.");
        table.getTableHeader().addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_LEFT) headerColumn = Math.max(0, headerColumn - 1);
                else if (e.getKeyCode() == KeyEvent.VK_RIGHT) headerColumn = Math.min(model.getColumnCount() - 1, headerColumn + 1);
                else if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_SPACE) sorter.toggleSortOrder(headerColumn);
                else return;
                e.consume(); table.getTableHeader().repaint();
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
            button.addActionListener(e -> { selectedDetail = index; ((CardLayout) detailCards.getLayout()).show(detailCards, view); });
            detailGroup.add(button); detailNavigation.add(button);
            detailCards.add(new JScrollPane(bodies[i]), view);
        }
        overview.getAccessibleContext().setAccessibleName("Request overview"); request.getAccessibleContext().setAccessibleName("Request headers and body"); response.getAccessibleContext().setAccessibleName("Response headers and body");
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0)); tools.add(copy); tools.add(curl);
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
        selectedRequest.setToolTipText(value == null ? null : text(value, "url"));
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
            setToolTipText(modelColumn == 1 || modelColumn == 5 ? text(record, "url") : Objects.toString(formatted, ""));
            return this;
        }
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
            setBorder(BorderFactory.createCompoundBorder(
                keyboardFocus ? BorderFactory.createLineBorder(accent, 2) : BorderFactory.createMatteBorder(0, 0, 1, 0, line),
                BorderFactory.createEmptyBorder(0, 10, 0, 10)));
            return this;
        }
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
