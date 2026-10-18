package mcboard;

/**
 * 一次服务器查询的结果(成功或失败都用一个对象表示)。
 */
public final class PlayerCountResult {

    private final boolean ok;
    private final String edition;
    private final int online;
    private final int max;
    private final String motd;
    private final String version;
    private final long latencyMs;
    private final String error;
    private final long fetchedAt;

    private PlayerCountResult(boolean ok, String edition, int online, int max, String motd,
                              String version, long latencyMs, String error) {
        this.ok = ok;
        this.edition = edition;
        this.online = online;
        this.max = max;
        this.motd = motd == null ? "" : motd;
        this.version = version == null ? "" : version;
        this.latencyMs = latencyMs;
        this.error = error == null ? "" : error;
        this.fetchedAt = System.currentTimeMillis();
    }

    public static PlayerCountResult success(String edition, int online, int max, String motd,
                                            String version, long latencyMs) {
        return new PlayerCountResult(true, edition, online, max, motd, version, latencyMs, null);
    }

    public static PlayerCountResult failure(String edition, long latencyMs, String error) {
        return new PlayerCountResult(false, edition, 0, 0, "", "", latencyMs, error);
    }

    public boolean isOk() {
        return ok;
    }

    public String getEdition() {
        return edition;
    }

    public int getOnline() {
        return online;
    }

    public int getMax() {
        return max;
    }

    public String getMotd() {
        return motd;
    }

    public String getVersion() {
        return version;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public String getError() {
        return error;
    }

    public long getFetchedAt() {
        return fetchedAt;
    }

    /** 把 MOTD 里的换行压成一行,避免破坏 Board.xaml 的结构。 */
    public String getMotdOneLine() {
        return motd.replace("\r", " ").replace("\n", " ").trim();
    }

    @Override
    public String toString() {
        if (ok) {
            return "在线 " + online + (max > 0 ? "/" + max : "") + " [" + edition + " " + latencyMs + "ms]";
        }
        return "查询失败 [" + edition + " " + latencyMs + "ms] " + error;
    }
}
