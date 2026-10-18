package mcboard;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 【功能三 / 功能四】内置 HTTP 服务:
 * 收到 Board.xaml 下载请求时,现场查询人数(功能一),把占位符替换成真实数字(功能三),
 * 然后返回文件 —— 也就是"每次下载都是最新的"。
 */
public final class LiveBoardService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final Config config;
    private final getServerPlayerCount playerCount;
    private final getBoardConfig board;
    private final PlayerCountCache cache;

    private HttpServer server;
    private ExecutorService pool;

    public LiveBoardService(Config config) {
        this.config = config;
        this.playerCount = new getServerPlayerCount(config);
        this.board = new getBoardConfig(config.getBoardFile(), config.getBoardCharset(),
                config.getPlaceholder(), config.isExtras(), config.isReloadOnChange());
        this.cache = new PlayerCountCache(playerCount, config.getCacheMs(), config.getDedupeMs(),
                config.isRefreshOnRequest());
    }

    public getServerPlayerCount getPlayerCounter() {
        return playerCount;
    }

    public getBoardConfig getBoard() {
        return board;
    }

    public void start() throws IOException {
        InetSocketAddress address = new InetSocketAddress(config.getHttpBind(), config.getHttpPort());
        server = HttpServer.create(address, 64);
        pool = Executors.newFixedThreadPool(Math.max(2, config.getHttpThreads()), new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "board-http-" + seq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
    }

    public int getPort() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    /** 启动时先查一次,便于在控制台立刻看到目标服务器是否可达。 */
    public PlayerCountResult warmUp() {
        return cache.get();
    }

    // ------------------------------------------------------------------
    // 路由
    // ------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            boolean head = "HEAD".equalsIgnoreCase(method);
            if (!head && !"GET".equalsIgnoreCase(method)) {
                send(exchange, 405, "text/plain; charset=utf-8",
                        "Method Not Allowed".getBytes(StandardCharsets.UTF_8), null, false);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String key = path.toLowerCase(Locale.ROOT);
            String boardPath = config.getHttpPath().toLowerCase(Locale.ROOT);

            if (key.equals(boardPath) || key.equals("/board.xaml") || key.equals("/download") || key.equals("/board")) {
                handleBoard(exchange, head, false);
            } else if (key.equals("/download/inline") || key.equals("/board.xaml/inline")) {
                handleBoard(exchange, head, true);
            } else if (key.equals("/status") || key.equals("/api/status")) {
                handleStatus(exchange, head);
            } else if (key.equals("/health")) {
                send(exchange, 200, "text/plain; charset=utf-8",
                        "ok".getBytes(StandardCharsets.UTF_8), noCacheHeaders(), head);
            } else if (key.equals("/") || key.equals("/index.html")) {
                handleIndex(exchange, head);
            } else {
                send(exchange, 404, "text/plain; charset=utf-8",
                        ("Not Found: " + path).getBytes(StandardCharsets.UTF_8), null, head);
            }
        } catch (Exception ex) {
            Log.error("处理请求失败: %s", ex.toString());
            if (Log.isDebugEnabled()) {
                ex.printStackTrace();
            }
            try {
                send(exchange, 500, "text/plain; charset=utf-8",
                        ("Internal Error: " + ex.getMessage()).getBytes(StandardCharsets.UTF_8), null, false);
            } catch (IOException ignored) {
                // 响应可能已经发出
            }
        } finally {
            exchange.close();
        }
    }

    // ------------------------------------------------------------------
    // Board.xaml 下载(功能三 + 功能四)
    // ------------------------------------------------------------------

    private void handleBoard(HttpExchange exchange, boolean head, boolean inline) throws IOException {
        long start = System.nanoTime();
        PlayerCountResult result = cache.get();

        String state;
        String body;
        int servedOnline;
        int servedMax;
        int status = 200;

        if (result.isOk()) {
            body = board.render(result);
            servedOnline = result.getOnline();
            servedMax = result.getMax();
            state = config.isRefreshOnRequest() ? "live" : "cached";
        } else {
            switch (config.getOnErrorMode()) {
                case "keep-placeholder" -> {
                    body = board.getContent();
                    servedOnline = -1;
                    servedMax = -1;
                    state = "raw";
                }
                case "zero", "last-known" -> {
                    PlayerCountResult good = cache.getLastGood();
                    int online;
                    int max;
                    if ("last-known".equals(config.getOnErrorMode()) && good != null) {
                        online = good.getOnline();
                        max = good.getMax();
                        state = "stale";
                    } else {
                        online = config.getFallbackCount();
                        max = good != null ? good.getMax() : 0;
                        state = "fallback";
                    }
                    servedOnline = online;
                    servedMax = max;
                    PlayerCountResult synthetic = PlayerCountResult.success(result.getEdition(), online, max,
                            result.getMotd(), result.getVersion(), result.getLatencyMs());
                    body = board.render(synthetic);
                }
                default -> {
                    // fail:直接把错误抛给调用方
                    String message = "服务器离线,无法生成 Board.xaml: " + result.getError();
                    Map<String, String> headers = noCacheHeaders();
                    headers.put("X-Player-Count", "-1");
                    send(exchange, 503, "text/plain; charset=utf-8",
                            message.getBytes(StandardCharsets.UTF_8), headers, head);
                    Log.warn("下载失败(离线且 on-error=fail): %s", result.getError());
                    return;
                }
            }
        }

        byte[] bytes = body.getBytes(board.getCharset());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        headers.put("Pragma", "no-cache");
        headers.put("Expires", "0");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("X-Player-Count", String.valueOf(servedOnline));
        headers.put("X-Player-Max", String.valueOf(servedMax));
        headers.put("X-Board-State", state);
        headers.put("X-Ping-Ms", String.valueOf(result.getLatencyMs()));
        headers.put("X-Board-Generated", Instant.now().toString());
        if (!inline) {
            headers.put("Content-Disposition", contentDisposition(config.getDownloadName()));
        }

        String contentType = guessContentType(config.getDownloadName(), board.getCharset());
        send(exchange, status, contentType, bytes, headers, head);

        long costMs = (System.nanoTime() - start) / 1_000_000L;
        Log.debug("下载 Board.xaml -> %d 人 (state=%s, %d ms)", servedOnline, state, costMs);
    }

    private void handleStatus(HttpExchange exchange, boolean head) throws IOException {
        PlayerCountResult result = cache.get();
        boolean fresh = config.getCacheMs() > 0 && cache.isFresh();
        StringBuilder json = new StringBuilder(256);
        json.append('{')
                .append("\"ok\":").append(result.isOk())
                .append(",\"online\":").append(result.isOk() ? result.getOnline() : -1)
                .append(",\"max\":").append(result.isOk() ? result.getMax() : -1)
                .append(",\"motd\":").append(Json.quote(result.getMotdOneLine()))
                .append(",\"version\":").append(Json.quote(result.getVersion()))
                .append(",\"edition\":").append(Json.quote(result.getEdition()))
                .append(",\"latencyMs\":").append(result.getLatencyMs())
                .append(",\"cached\":").append(fresh)
                .append(",\"error\":").append(result.isOk() ? "null" : Json.quote(result.getError()))
                .append(",\"server\":").append(Json.quote(config.getServerAddress()))
                .append(",\"targetEdition\":").append(Json.quote(config.getServerEdition()))
                .append(",\"boardFile\":").append(Json.quote(board.getFile().getName()))
                .append(",\"boardCharset\":").append(Json.quote(board.getCharset().name()))
                .append(",\"updatedAt\":").append(Json.quote(TIME.format(Instant.ofEpochMilli(result.getFetchedAt()))))
                .append('}');
        send(exchange, 200, "application/json; charset=utf-8",
                json.toString().getBytes(StandardCharsets.UTF_8), noCacheHeaders(), head);
    }

    private void handleIndex(HttpExchange exchange, boolean head) throws IOException {
        PlayerCountResult result = cache.get();
        String count = result.isOk() ? String.valueOf(result.getOnline()) : "-1";
        String color = result.isOk() ? "#22c55e" : "#ef4444";
        String statusText = result.isOk() ? "在线" : "离线";
        String html = """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>BoardLive - 实时 Board.xaml</title>
                  <style>
                    :root { color-scheme: dark; }
                    body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center;
                           background:#0b1020; color:#e6edf7;
                           font-family:system-ui,-apple-system,"Segoe UI","Microsoft YaHei",sans-serif; }
                    .card { width:min(680px,92vw); background:#121a33; border:1px solid #24304f;
                            border-radius:18px; padding:28px 30px; box-shadow:0 18px 45px rgba(0,0,0,.45); }
                    h1 { margin:0 0 4px; font-size:20px; letter-spacing:.5px; }
                    .sub { color:#8fa3c8; font-size:13px; margin-bottom:22px; word-break:break-all; }
                    .row { display:flex; align-items:baseline; gap:14px; }
                    .num { font-size:64px; font-weight:700; line-height:1; color:COUNT_COLOR; }
                    .unit { color:#8fa3c8; font-size:14px; }
                    .meta { margin:18px 0 24px; display:grid; gap:8px; font-size:13px; color:#a9bbd8; }
                    .meta b { color:#e6edf7; font-weight:600; }
                    .btn { display:inline-block; padding:11px 18px; border-radius:10px; text-decoration:none;
                           background:#2f6bff; color:#fff; font-size:14px; font-weight:600; }
                    .btn.secondary { background:#1d2846; color:#cddaf2; margin-left:8px; }
                    .btn:hover { filter:brightness(1.1); }
                    .foot { margin-top:22px; font-size:12px; color:#6d7f9e; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>BoardLive</h1>
                    <div class="sub">目标服务器 <b>SERVER_ADDR</b> · 状态 STATUS_TEXT · 延迟 PING_MS ms</div>
                    <div class="row">
                      <div class="num">PLAYER_COUNT</div>
                      <div class="unit">/ PLAYER_MAX 在线玩家</div>
                    </div>
                    <div class="meta">
                      <div>MOTD:<b>MOTD_TEXT</b></div>
                      <div>版本:<b>VERSION_TEXT</b> · 类型:<b>EDITION_TEXT</b></div>
                      <div>Board 模板:<b>BOARD_FILE</b>(BOARD_CHARSET)</div>
                      <div>生成时间:<b>UPDATED_AT</b></div>
                    </div>
                    <a class="btn" href="DOWNLOAD_PATH">下载 Board.xaml</a>
                    <a class="btn secondary" href="/status">查看 JSON 状态</a>
                    <div class="foot">每次下载都会现场查询在线人数,页面每 3 秒自动刷新。</div>
                  </div>
                  <script>
                    (function () {
                      var timer = setInterval(function () {
                        fetch('/status', { cache: 'no-store' })
                          .then(function (r) { return r.json(); })
                          .then(function (d) {
                            if (!d.ok) return;
                            document.querySelector('.num').textContent = d.online;
                            document.querySelector('.unit').textContent = '/ ' + d.max + ' 在线玩家';
                          })
                          .catch(function () {});
                      }, 3000);
                      window.addEventListener('beforeunload', function () { clearInterval(timer); });
                    })();
                  </script>
                </body>
                </html>
                """;
        html = html
                .replace("COUNT_COLOR", color)
                .replace("PLAYER_COUNT", escapeHtml(count))
                .replace("PLAYER_MAX", escapeHtml(result.isOk() ? String.valueOf(result.getMax()) : "-1"))
                .replace("SERVER_ADDR", escapeHtml(config.getServerAddress()))
                .replace("STATUS_TEXT", statusText)
                .replace("PING_MS", String.valueOf(result.getLatencyMs()))
                .replace("MOTD_TEXT", escapeHtml(result.getMotdOneLine()))
                .replace("VERSION_TEXT", escapeHtml(result.getVersion()))
                .replace("EDITION_TEXT", escapeHtml(result.getEdition()))
                .replace("BOARD_FILE", escapeHtml(board.getFile().getName()))
                .replace("BOARD_CHARSET", escapeHtml(board.getCharset().name()))
                .replace("UPDATED_AT", TIME.format(Instant.ofEpochMilli(result.getFetchedAt())))
                .replace("DOWNLOAD_PATH", escapeHtml(config.getHttpPath()));
        send(exchange, 200, "text/html; charset=utf-8",
                html.getBytes(StandardCharsets.UTF_8), noCacheHeaders(), head);
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private static Map<String, String> noCacheHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        headers.put("Pragma", "no-cache");
        headers.put("Access-Control-Allow-Origin", "*");
        return headers;
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body,
                             Map<String, String> headers, boolean head) throws IOException {
        Headers responseHeaders = exchange.getResponseHeaders();
        if (contentType != null) {
            responseHeaders.set("Content-Type", contentType);
        }
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                responseHeaders.set(entry.getKey(), entry.getValue());
            }
        }
        if (head) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
                out.flush();
            }
        }
    }

    private static String contentDisposition(String fileName) {
        String asciiFallback = fileName.matches("[\\x20-\\x7E]+") ? fileName : "Board.xaml";
        String encoded;
        try {
            encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20").replace("*", "%2A");
        } catch (Exception ex) {
            encoded = "Board.xaml";
        }
        return "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encoded;
    }

    private static String guessContentType(String fileName, Charset charset) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        String base;
        if (lower.endsWith(".xaml")) {
            base = "application/xaml+xml";
        } else if (lower.endsWith(".xml")) {
            base = "application/xml";
        } else if (lower.endsWith(".json")) {
            base = "application/json";
        } else if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            base = "text/yaml";
        } else if (lower.endsWith(".txt")) {
            base = "text/plain";
        } else {
            base = "application/octet-stream";
        }
        if (base.startsWith("text/") || base.endsWith("+xml") || base.equals("application/json")) {
            return base + "; charset=" + charset.name().toLowerCase(Locale.ROOT);
        }
        return base;
    }

    private static String escapeHtml(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
