package mcboard;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * config.yml 的读取与校验。配置项全部有默认值,缺项不会导致启动失败。
 */
public final class Config {

    /** 找不到 config.yml 时会写出的默认配置。 */
    public static final String DEFAULT_YAML = """
            # ============================================================
            #  BoardLive - 实时在线人数 Board.xaml 分发服务(独立应用)
            #  说明:所有相对路径都相对于本文件所在目录
            # ============================================================

            # ---------- 目标 Minecraft 服务器(功能一:getServerPlayerCount) ----------
            server:
              # 目标服务器地址,支持 "host:port" 一行写法
              address: "127.0.0.1:25565"
              # 也可以分开写(会覆盖 address 中对应的部分)
              # host: "play.example.com"
              # port: 25565
              # 版本类型: java / bedrock / auto(auto = 先试 Java 版,失败再试基岩版)
              edition: "auto"
              # 连接超时(毫秒)
              timeout-ms: 3000
              # 失败重试次数(0 = 不重试)
              retries: 1
              # Java 版列表查询使用的协议号,一般不用改(47 = 1.8,任何版本都能查询)
              protocol-version: 47

            # ---------- 对外 HTTP 服务(功能三:提供下载) ----------
            http:
              # 监听地址,0.0.0.0 表示所有网卡
              bind: "0.0.0.0"
              # 下载端口
              port: 8080
              # Board.xaml 的下载路径(大小写不敏感;/board.xaml 与 /download 也始终可用)
              path: "/Board.xaml"
              # 浏览器下载时显示的文件名
              download-name: "Board.xaml"
              # 工作线程数
              threads: 8

            # ---------- Board 模板(功能二:getBoardConfig) ----------
            board:
              file: "Board.xaml"
              # auto / UTF-8 / GBK / UTF-16 ...(auto 会按 BOM 与内容自动判断)
              charset: "auto"
              # 人数占位符
              placeholder: "%player_count%"
              # Board.xaml 被修改后自动重新加载,无需重启
              reload-on-change: true
              # 是否额外替换 %player_max% %motd% %version% %ping_ms% 等扩展占位符
              extras: true

            # ---------- 刷新策略(功能四:下载即实时查询) ----------
            refresh:
              # true = 每次下载请求都重新查询服务器(实时);false = 只用缓存
              on-request: true
              # 结果缓存毫秒数,0 = 不使用缓存
              cache-ms: 0
              # 并发去重窗口(毫秒):窗口内的并发下载复用同一次查询,避免打爆目标服务器
              dedupe-ms: 200
              # 查询失败时怎么办: zero / last-known / keep-placeholder / fail
              on-error: "last-known"
              # on-error = zero,或还没有任何一次成功记录时使用的数字
              fallback-player-count: 0

            # ---------- 日志 ----------
            logging:
              debug: false
            """;

    private final File file;
    private final File baseDir;
    private final Map<String, String> values;

    private final String host;
    private final int port;
    private final String edition;
    private final int timeoutMs;
    private final int retries;
    private final int protocolVersion;

    private int httpPort;
    private final String httpBind;
    private final String httpPath;
    private final String downloadName;
    private final int httpThreads;

    private final File boardFile;
    private final Charset boardCharset;
    private final String placeholder;
    private final boolean reloadOnChange;
    private final boolean extras;

    private final boolean refreshOnRequest;
    private final long cacheMs;
    private final long dedupeMs;
    private final String onError;
    private final int fallbackCount;

    private final boolean debug;

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    public static Config load(File file) throws IOException {
        File absolute = file.getAbsoluteFile();
        if (!absolute.isFile()) {
            throw new IOException("找不到配置文件: " + absolute);
        }
        return parse(Files.readString(absolute.toPath(), StandardCharsets.UTF_8), absolute);
    }

    public static Config parse(String yaml, File file) {
        return new Config(file.getAbsoluteFile(), SimpleYaml.parse(yaml));
    }

    /** 读取配置;文件不存在时先写出默认配置。 */
    public static Config loadOrCreate(File file) throws IOException {
        File absolute = file.getAbsoluteFile();
        if (!absolute.isFile()) {
            writeDefault(absolute);
            Log.warn("未找到配置文件,已生成默认配置: %s", absolute.getPath());
        }
        return load(absolute);
    }

    public static void writeDefault(File file) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory()) {
            Files.createDirectories(parent.toPath());
        }
        Files.writeString(file.toPath(), DEFAULT_YAML, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // 构造 / 校验
    // ------------------------------------------------------------------

    private Config(File file, Map<String, String> values) {
        this.file = file;
        this.baseDir = file.getParentFile() != null ? file.getParentFile() : new File(".");
        this.values = values;

        // ---- 目标服务器 ----
        String address = firstNonEmpty(get("server.address", ""), get("mc-ip", ""), get("ip", ""));
        String hostValue = get("server.host", "");
        int portValue = getInt("server.port", -1);
        if (!address.isEmpty()) {
            String[] parts = splitAddress(address);
            if (hostValue.isEmpty()) {
                hostValue = parts[0];
            }
            if (portValue <= 0) {
                portValue = parseIntLenient(parts[1], 25565);
            }
        }
        this.host = hostValue.isEmpty() ? "127.0.0.1" : hostValue;
        this.port = portValue <= 0 ? 25565 : portValue;
        this.edition = normalizeEdition(firstNonEmpty(get("server.edition", ""), get("edition", ""), "auto"));
        this.timeoutMs = clampInt(getInt("server.timeout-ms", 3000), 100, 120_000);
        this.retries = clampInt(getInt("server.retries", 1), 0, 10);
        this.protocolVersion = getInt("server.protocol-version", 47);

        // ---- HTTP ----
        this.httpPort = clampInt(getInt("http.port", 8080), 1, 65535);
        this.httpBind = firstNonEmpty(get("http.bind", ""), "0.0.0.0");
        this.httpPath = normalizePath(get("http.path", "/Board.xaml"));
        this.downloadName = sanitizeFileName(get("http.download-name", "Board.xaml"));
        this.httpThreads = clampInt(getInt("http.threads", 8), 2, 256);

        // ---- Board ----
        this.boardFile = resolve(get("board.file", "Board.xaml"));
        String charsetName = get("board.charset", "auto");
        Charset parsed = null;
        if (!"auto".equalsIgnoreCase(charsetName)) {
            try {
                parsed = Charset.forName(charsetName);
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("board.charset 无法识别: " + charsetName, ex);
            }
        }
        this.boardCharset = parsed;
        this.placeholder = firstNonEmpty(get("board.placeholder", ""), "%player_count%");
        this.reloadOnChange = getBoolean("board.reload-on-change", true);
        this.extras = getBoolean("board.extras", true);

        // ---- 刷新 ----
        this.refreshOnRequest = getBoolean("refresh.on-request", true);
        this.cacheMs = Math.max(0L, getLong("refresh.cache-ms", 0L));
        this.dedupeMs = Math.max(0L, getLong("refresh.dedupe-ms", 200L));
        this.onError = normalizeOnError(get("refresh.on-error", "last-known"));
        this.fallbackCount = Math.max(0, getInt("refresh.fallback-player-count", 0));

        // ---- 日志 ----
        this.debug = getBoolean("logging.debug", false);
    }

    // ------------------------------------------------------------------
    // 取值辅助
    // ------------------------------------------------------------------

    private String get(String key, String defaultValue) {
        String value = values.get(key);
        return value == null ? defaultValue : value;
    }

    private int getInt(String key, int defaultValue) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("config.yml 中 " + key + " 必须是整数,当前值: " + raw);
        }
    }

    private long getLong(String key, long defaultValue) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("config.yml 中 " + key + " 必须是整数,当前值: " + raw);
        }
    }

    private boolean getBoolean(String key, boolean defaultValue) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> throw new IllegalArgumentException(
                    "config.yml 中 " + key + " 必须是 true/false,当前值: " + raw);
        };
    }

    private static String firstNonEmpty(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }

    private static int parseIntLenient(String raw, int defaultValue) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 支持 "host:port"、"[::1]:port"、"host"。 */
    static String[] splitAddress(String address) {
        String trimmed = address.trim();
        String hostPart = trimmed;
        String portPart = "";
        if (trimmed.startsWith("[")) {
            int end = trimmed.indexOf(']');
            if (end > 0) {
                hostPart = trimmed.substring(1, end);
                if (end + 1 < trimmed.length() && trimmed.charAt(end + 1) == ':') {
                    portPart = trimmed.substring(end + 2);
                }
            }
        } else {
            int colon = trimmed.lastIndexOf(':');
            if (colon > 0 && trimmed.indexOf(':') == colon) {
                hostPart = trimmed.substring(0, colon);
                portPart = trimmed.substring(colon + 1);
            }
        }
        return new String[]{hostPart, portPart};
    }

    private static String normalizeEdition(String raw) {
        String v = raw == null ? "auto" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "java", "je", "java-edition" -> "java";
            case "bedrock", "be", "pe", "bedrock-edition" -> "bedrock";
            case "auto", "" -> "auto";
            default -> throw new IllegalArgumentException(
                    "server.edition 只能是 java / bedrock / auto,当前值: " + raw);
        };
    }

    private static String normalizeOnError(String raw) {
        String v = raw == null ? "last-known" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "zero", "last-known", "keep-placeholder", "fail" -> v;
            default -> throw new IllegalArgumentException(
                    "refresh.on-error 只能是 zero / last-known / keep-placeholder / fail,当前值: " + raw);
        };
    }

    private static String normalizePath(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) {
            return "/Board.xaml";
        }
        return v.startsWith("/") ? v : "/" + v;
    }

    private static String sanitizeFileName(String raw) {
        String v = raw == null ? "Board.xaml" : raw.trim();
        if (v.isEmpty()) {
            v = "Board.xaml";
        }
        return v.replace("\"", "").replace("\r", "").replace("\n", "");
    }

    private File resolve(String path) {
        File candidate = new File(path);
        if (candidate.isAbsolute()) {
            return candidate;
        }
        return new File(baseDir, path).getAbsoluteFile();
    }

    // ------------------------------------------------------------------
    // 对外只读视图
    // ------------------------------------------------------------------

    public File getFile() {
        return file;
    }

    public File getBaseDir() {
        return baseDir;
    }

    public String getServerHost() {
        return host;
    }

    public int getServerPort() {
        return port;
    }

    public String getServerAddress() {
        return host + ":" + port;
    }

    public String getServerEdition() {
        return edition;
    }

    public int getServerTimeoutMs() {
        return timeoutMs;
    }

    public int getServerRetries() {
        return retries;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public int getHttpPort() {
        return httpPort;
    }

    public void overrideHttpPort(int port) {
        this.httpPort = clampInt(port, 1, 65535);
    }

    public String getHttpBind() {
        return httpBind;
    }

    public String getHttpPath() {
        return httpPath;
    }

    public String getDownloadName() {
        return downloadName;
    }

    public int getHttpThreads() {
        return httpThreads;
    }

    public File getBoardFile() {
        return boardFile;
    }

    /** null 表示自动识别编码。 */
    public Charset getBoardCharset() {
        return boardCharset;
    }

    public String getBoardCharsetName() {
        return boardCharset == null ? "auto" : boardCharset.name();
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public boolean isReloadOnChange() {
        return reloadOnChange;
    }

    public boolean isExtras() {
        return extras;
    }

    public boolean isRefreshOnRequest() {
        return refreshOnRequest;
    }

    public long getCacheMs() {
        return cacheMs;
    }

    public long getDedupeMs() {
        return dedupeMs;
    }

    public String getOnErrorMode() {
        return onError;
    }

    public int getFallbackCount() {
        return fallbackCount;
    }

    public boolean isDebug() {
        return debug;
    }

    public Map<String, String> getRawValues() {
        return new LinkedHashMap<>(values);
    }
}
