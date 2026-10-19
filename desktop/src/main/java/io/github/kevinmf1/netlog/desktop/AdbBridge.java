package io.github.kevinmf1.netlog.desktop;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class AdbBridge implements AutoCloseable {
    record Batch(List<JsonObject> records, String status) { }
    private static final class Connection {
        final String serial, name;
        final int port;
        long cursor;
        String session = "";
        Connection(String serial, String name, int port) { this.serial = serial; this.name = name; this.port = port; }
    }
    private final Map<String, Connection> connections = new LinkedHashMap<>();
    private String executable;

    AdbBridge() { executable = findAdb(); }
    synchronized void setExecutable(String path) { close(); executable = path; }

    static String findAdb() {
        List<Path> paths = new ArrayList<>();
        String binary = System.getProperty("os.name").startsWith("Windows") ? "adb.exe" : "adb";
        try {
            Path jar = Paths.get(AdbBridge.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            paths.add(jar.getParent().resolve("platform-tools").resolve(binary));
        } catch (Exception ignored) { }
        for (String env : List.of("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            String sdk = System.getenv(env);
            if (sdk != null) paths.add(Paths.get(sdk, "platform-tools", binary));
        }
        String user = System.getProperty("user.home");
        paths.add(Paths.get(user, "Library/Android/sdk/platform-tools", binary));
        paths.add(Paths.get(user, "Android/Sdk/platform-tools", binary));
        paths.add(Paths.get(user, "AppData/Local/Android/Sdk/platform-tools", binary));
        for (String folder : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) paths.add(Paths.get(folder, binary));
        return paths.stream().filter(Files::isExecutable).map(Path::toString).findFirst().orElse(null);
    }

    private String command(String... args) throws IOException {
        if (executable == null) throw new IOException("ADB not found. Use Locate ADB to select Android SDK platform-tools/adb.");
        List<String> command = new ArrayList<>(); command.add(executable); command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] bytes = new byte[8192]; int count;
                while ((count = input.read(bytes)) != -1) {
                    if (output.size() + count > 2 * 1024 * 1024) { process.destroyForcibly(); break; }
                    output.write(bytes, 0, count);
                }
            } catch (IOException ignored) { }
        }, "ADB output");
        reader.setDaemon(true); reader.start();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("ADB timed out; reconnecting automatically."); }
            reader.join(1000);
        } catch (InterruptedException error) { process.destroyForcibly(); Thread.currentThread().interrupt(); throw new IOException("ADB interrupted", error); }
        String result = output.toString(StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new IOException(result.strip());
        return result;
    }

    synchronized Batch poll() {
        List<JsonObject> records = new ArrayList<>();
        Set<String> active = new HashSet<>();
        List<String> notices = new ArrayList<>();
        int devices = 0, apps = 0;
        try {
            for (String line : command("devices", "-l").split("\\R")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2 || line.startsWith("List ") || line.startsWith("*")) continue;
                String serial = parts[0], state = parts[1];
                if (state.equals("unauthorized")) { notices.add(serial + ": allow USB debugging on the device"); continue; }
                if (!state.equals("device")) { notices.add(serial + ": " + state); continue; }
                devices++;
                try {
                    String sockets = command("-s", serial, "shell", "cat", "/proc/net/unix");
                    for (String name : listeningSockets(sockets)) {
                        String key = serial + "/" + name;
                        if (!active.add(key)) continue;
                        try {
                            Connection connection = connections.get(key);
                            if (connection == null) {
                                int port = Integer.parseInt(command("-s", serial, "forward", "tcp:0", "localabstract:" + name).strip());
                                connection = new Connection(serial, name, port); connections.put(key, connection);
                            }
                            records.addAll(fetch(connection)); apps++;
                        } catch (Exception error) {
                            Connection broken = connections.remove(key);
                            if (broken != null) remove(broken);
                            notices.add(serial + ": open the app to resume capture");
                        }
                    }
                } catch (IOException error) { notices.add(serial + ": " + error.getMessage()); }
            }
            Iterator<Map.Entry<String, Connection>> iterator = connections.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Connection> entry = iterator.next();
                if (!active.contains(entry.getKey())) { remove(entry.getValue()); iterator.remove(); }
            }
            String status = apps > 0 ? "Connected to " + apps + " app session(s) on " + devices + " device(s). Capturing automatically."
                : devices > 0 ? devices + " device(s) ready. Open an app with NetLog installed to see requests."
                : "Start an emulator or connect a phone with USB debugging enabled.";
            if (!notices.isEmpty()) status += "  " + String.join(" • ", notices);
            return new Batch(records, status);
        } catch (IOException error) { return new Batch(records, error.getMessage()); }
    }

    static Set<String> listeningSockets(String table) {
        Set<String> names = new LinkedHashSet<>();
        for (String line : table.split("\\R")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 8 || !fields[3].equals("00010000")) continue;
            String name = fields[7];
            if (name.matches("@netlog\\.v1\\.[A-Za-z0-9_.]+")) names.add(name.substring(1));
        }
        return names;
    }

    private List<JsonObject> fetch(Connection connection) throws IOException {
        List<JsonObject> result = new ArrayList<>();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", connection.port), 1500);
            socket.setSoTimeout(2000);
            socket.getOutputStream().write((connection.cursor + "\n").getBytes(StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(new InputStreamReader(new LimitedInput(socket.getInputStream()), StandardCharsets.UTF_8));
            String first = reader.readLine();
            if (first == null) throw new IOException("App disconnected");
            JsonObject hello = JsonParser.parseString(first).getAsJsonObject();
            if (!hello.get("type").getAsString().equals("hello") || hello.get("version").getAsInt() != 1) throw new IOException("Unsupported NetLog protocol");
            String session = hello.get("session").getAsString();
            if (!connection.session.isEmpty() && !connection.session.equals(session)) {
                connection.session = session; connection.cursor = 0; return result;
            }
            connection.session = session;
            String line; boolean ended = false;
            while ((line = reader.readLine()) != null) {
                JsonObject record = JsonParser.parseString(line).getAsJsonObject();
                if (record.get("type").getAsString().equals("end")) {
                    connection.cursor = record.get("cursor").getAsLong(); ended = true; break;
                }
                record.addProperty("device", connection.serial);
                record.addProperty("app", hello.get("app").getAsString());
                record.addProperty("package", hello.get("package").getAsString());
                record.addProperty("session", session);
                record.addProperty("key", connection.serial + "/" + session + "/" + record.get("id").getAsLong());
                result.add(record);
            }
            if (!ended) throw new IOException("Incomplete capture batch");
        }
        return result;
    }

    private static final class LimitedInput extends FilterInputStream {
        int remaining = 16 * 1024 * 1024;
        LimitedInput(InputStream input) { super(input); }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining <= 0) throw new IOException("Capture batch too large");
            int count = in.read(bytes, offset, Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
        @Override public int read() throws IOException {
            if (--remaining < 0) throw new IOException("Capture batch too large");
            return in.read();
        }
    }

    private void remove(Connection connection) {
        try { command("-s", connection.serial, "forward", "--remove", "tcp:" + connection.port); } catch (IOException ignored) { }
    }
    @Override public synchronized void close() { connections.values().forEach(this::remove); connections.clear(); }
}
