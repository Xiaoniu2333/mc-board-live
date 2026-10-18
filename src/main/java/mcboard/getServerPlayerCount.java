package mcboard;

import java.io.File;
import java.io.IOException;

/**
 * 【功能一】读取指定 MC 服务器(地址来自 config.yml)的在线人数。
 *
 * <p>用法:
 * <pre>
 *   Config cfg = Config.load(new File("config.yml"));
 *   int online = new getServerPlayerCount(cfg).fetchPlayerCount();   // -1 表示失败
 *
 *   PlayerCountResult detail = new getServerPlayerCount(cfg).fetch();
 *   detail.getOnline(); detail.getMax(); detail.getLatencyMs(); ...
 * </pre>
 */
public class getServerPlayerCount {

    private final Config config;

    public getServerPlayerCount(Config config) {
        if (config == null) {
            throw new IllegalArgumentException("config 不能为空");
        }
        this.config = config;
    }

    public getServerPlayerCount(File configFile) throws IOException {
        this(Config.load(configFile));
    }

    public Config getConfig() {
        return config;
    }

    /**
     * 查询一次服务器状态,失败也返回对象(通过 {@link PlayerCountResult#isOk()} 判断)。
     */
    public PlayerCountResult fetch() {
        String edition = config.getServerEdition();
        return switch (edition) {
            case "java" -> fetchJava();
            case "bedrock" -> fetchBedrock();
            default -> fetchAuto();
        };
    }

    /** 只取在线人数;失败返回 -1。 */
    public int fetchPlayerCount() {
        PlayerCountResult result = fetch();
        return result.isOk() ? result.getOnline() : -1;
    }

    private PlayerCountResult fetchAuto() {
        PlayerCountResult javaResult = fetchJava();
        if (javaResult.isOk()) {
            return javaResult;
        }
        Log.debug("Java 版查询失败,继续尝试基岩版: %s", javaResult.getError());
        PlayerCountResult bedrockResult = fetchBedrock();
        if (bedrockResult.isOk()) {
            return bedrockResult;
        }
        return PlayerCountResult.failure("auto", javaResult.getLatencyMs() + bedrockResult.getLatencyMs(),
                "Java: " + javaResult.getError() + " | Bedrock: " + bedrockResult.getError());
    }

    private PlayerCountResult fetchJava() {
        int attempts = config.getServerRetries() + 1;
        PlayerCountResult last = null;
        for (int i = 0; i < attempts; i++) {
            last = JavaStatusPinger.fetch(config.getServerHost(), config.getServerPort(),
                    config.getProtocolVersion(), config.getServerTimeoutMs());
            if (last.isOk()) {
                return last;
            }
            if (i + 1 < attempts) {
                Log.debug("Java 版查询失败(%s),第 %d 次重试", last.getError(), i + 1);
            }
        }
        return last;
    }

    private PlayerCountResult fetchBedrock() {
        int attempts = config.getServerRetries() + 1;
        PlayerCountResult last = null;
        for (int i = 0; i < attempts; i++) {
            last = BedrockPinger.fetch(config.getServerHost(), config.getServerPort(), config.getServerTimeoutMs());
            if (last.isOk()) {
                return last;
            }
            if (i + 1 < attempts) {
                Log.debug("基岩版查询失败(%s),第 %d 次重试", last.getError(), i + 1);
            }
        }
        return last;
    }

    /** 命令行入口: java -cp BoardLive.jar mcboard.getServerPlayerCount [config.yml] */
    public static void main(String[] args) throws Exception {
        Log.configureConsole();
        File configFile = new File(args.length > 0 ? args[0] : "config.yml");
        Config config = Config.load(configFile);
        Log.setDebugEnabled(config.isDebug());
        PlayerCountResult result = new getServerPlayerCount(config).fetch();
        if (!result.isOk()) {
            System.err.println(result.getError());
            System.exit(2);
        }
        System.out.println(result.getOnline());
    }
}
