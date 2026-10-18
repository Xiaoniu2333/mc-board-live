package mcboard;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;

/**
 * 独立应用入口。
 *
 * <pre>
 *   java -jar BoardLive.jar                启动服务
 *   java -jar BoardLive.jar -p 9000        用自定义端口启动
 *   java -jar BoardLive.jar -c D:\x\config.yml
 * </pre>
 *
 * 默认读取与 BoardLive.jar 同目录的 config.yml 与 Board.xaml;
 * 首次运行(文件不存在)会自动生成默认配置和示例模板。
 */
public final class Main {

    public static final String VERSION = "1.1.0";

    private static final String CONFIG_NAME = "config.yml";
    private static final String BOARD_NAME = "Board.xaml";

    private static final String SAMPLE_BOARD = """
            <?xml version="1.0" encoding="utf-8"?>
            <!--
              Board 模板示例。
              把这里的内容替换成你真实的 Board.xaml 即可 ——
              服务本身只做一件事:把 %player_count% 换成当前在线人数。

              支持变量:
                %player_count%    当前在线人数(核心变量,功能二)
                %player_max%      最大人数        (board.extras: true 时生效)
                %motd%            服务器 MOTD
                %version%         服务器版本
                %ping_ms%         查询耗时(毫秒)
                %server_edition%  java / bedrock
              未定义的 %xxx% 会原样保留,不会被清空。
            -->
            <Board Name="title" Version="1.0" UpdateInterval="1">
              <Group Name="default">
                <Line Index="1" Content="§6服务器 §f| §a在线 §e%player_count% §7/ §f%player_max%" />
                <Line Index="2" Content="§7版本 §f%version% §8· §7延迟 §f%ping_ms%ms" />
                <Line Index="3" Content="§7当前人数 §f%player_count% §8人" />
              </Group>
            </Board>
            """;

    private Main() {
    }

    public static void main(String[] args) {
        Log.configureConsole();
        try {
            run(args);
        } catch (IllegalArgumentException ex) {
            Log.error("%s", ex.getMessage());
            System.err.println();
            printHelp();
            System.exit(1);
        } catch (Exception ex) {
            Log.error("启动失败: %s", ex.getMessage());
            if (Log.isDebugEnabled()) {
                ex.printStackTrace();
            }
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        File baseDir = resolveBaseDir();
        File configFile = new File(baseDir, CONFIG_NAME);
        Integer portOverride = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-p", "--port" -> portOverride = parsePort(nextValue(args, ++i, arg));
                case "-c", "--config" -> configFile = new File(nextValue(args, ++i, arg)).getAbsoluteFile();
                case "--debug" -> Log.setDebugEnabled(true);
                case "--no-color" -> Log.setColorEnabled(false);
                case "-h", "--help" -> {
                    printHelp();
                    return;
                }
                case "-v", "--version" -> {
                    System.out.println("BoardLive " + VERSION + " (Java " + System.getProperty("java.version") + ")");
                    return;
                }
                case "serve" -> Log.warn("serve 命令已移除,直接 java -jar BoardLive.jar 即可(本次已忽略该参数)");
                default -> throw new IllegalArgumentException("不支持的参数: " + arg
                        + "(直接运行 java -jar BoardLive.jar 即可启动;可用参数见下方帮助)");
            }
        }

        start(baseDir, configFile, portOverride);
    }

    private static void start(File baseDir, File configFile, Integer portOverride) throws Exception {
        if (configFile.isDirectory()) {
            throw new IllegalArgumentException("配置路径是一个目录,请指向 config.yml 文件: " + configFile);
        }
        boolean createdConfig = ensureConfig(configFile);

        Config config = Config.load(configFile);
        if (portOverride != null) {
            config.overrideHttpPort(portOverride);
        }
        if (config.isDebug()) {
            Log.setDebugEnabled(true);
        }

        boolean createdBoard = ensureBoard(config.getBoardFile());

        if (createdConfig) {
            Log.warn("首次运行:已生成默认配置 %s", configFile.getPath());
            Log.warn("请修改其中的 server.address 与 http.port,然后重新启动。");
        }
        if (createdBoard) {
            Log.warn("已生成示例模板 %s,请替换成你自己的 Board.xaml。", config.getBoardFile().getPath());
        }

        LiveBoardService service = new LiveBoardService(config);
        service.start();

        String host = "0.0.0.0".equals(config.getHttpBind()) ? "127.0.0.1" : config.getHttpBind();
        String base = "http://" + host + ":" + service.getPort();
        System.out.println();
        Log.info("BoardLive %s 已启动(独立应用,无需 MC 服务端插件)", VERSION);
        Log.info("程序目录   : %s", baseDir.getPath());
        Log.info("配置文件   : %s", config.getFile().getPath());
        Log.info("目标服务器 : %s (%s)", config.getServerAddress(), config.getServerEdition());
        Log.info("Board 模板 : %s (%s)", config.getBoardFile().getPath(), config.getBoardCharsetName());
        Log.info("下载地址   : %s%s", base, config.getHttpPath());
        Log.info("状态页面   : %s/", base);

        PlayerCountResult warm = service.warmUp();
        if (warm.isOk()) {
            Log.info("当前人数   : %d / %d", warm.getOnline(), warm.getMax());
        } else {
            Log.warn("当前人数   : 查询失败(%s)", warm.getError());
        }
        System.out.println();
        Log.info("按 Ctrl+C 停止服务。");

        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("正在停止服务...");
            service.stop();
            latch.countDown();
        }, "board-shutdown"));
        latch.await();
    }

    // ------------------------------------------------------------------
    // 默认文件:不存在就生成
    // ------------------------------------------------------------------

    private static boolean ensureConfig(File configFile) throws Exception {
        if (configFile.isFile()) {
            return false;
        }
        Config.writeDefault(configFile);
        return true;
    }

    private static boolean ensureBoard(File boardFile) throws Exception {
        if (boardFile.isFile()) {
            return false;
        }
        File parent = boardFile.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory()) {
            Files.createDirectories(parent.toPath());
        }
        Files.writeString(boardFile.toPath(), SAMPLE_BOARD, StandardCharsets.UTF_8);
        return true;
    }

    // ------------------------------------------------------------------
    // 程序目录:jar 所在目录(开发环境下退化为当前目录)
    // ------------------------------------------------------------------

    static File resolveBaseDir() {
        try {
            var source = Main.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                File location = new File(source.getLocation().toURI());
                if (location.isFile()) {
                    File parent = location.getParentFile();
                    if (parent != null) {
                        return parent.getAbsoluteFile();
                    }
                }
            }
        } catch (Exception ignored) {
            // 某些环境拿不到 CodeSource,退回当前目录
        }
        return new File(".").getAbsoluteFile();
    }

    // ------------------------------------------------------------------
    // 参数 / 帮助
    // ------------------------------------------------------------------

    private static String nextValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("参数 " + option + " 缺少取值");
        }
        return args[index];
    }

    private static int parsePort(String raw) {
        int port;
        try {
            port = Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("端口必须是数字: " + raw);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口必须在 1-65535 之间: " + raw);
        }
        return port;
    }

    private static void printHelp() {
        System.out.println("""
                BoardLive - 实时在线人数 Board.xaml 分发服务(Java 17 独立应用)

                用法:
                  java -jar BoardLive.jar                     直接启动
                  java -jar BoardLive.jar -p 9000             用 9000 端口启动
                  java -jar BoardLive.jar -c D:\\x\\config.yml  使用指定配置文件

                默认读取与 BoardLive.jar 同目录的 config.yml 与 Board.xaml,
                首次启动若文件不存在会自动生成默认配置和示例模板。

                参数:
                  -p, --port <端口>     覆盖配置文件里的下载端口
                  -c, --config <文件>   指定配置文件(默认:jar 同目录的 config.yml)
                      --debug           输出调试日志
                      --no-color        关闭彩色日志
                  -h, --help            显示本帮助
                  -v, --version         显示版本
                """);
    }
}
