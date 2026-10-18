package mcboard;

/**
 * 人数查询缓存 + 并发去重。
 *
 * <p>功能四的"智能更新"核心:每个下载请求都会走到这里。缓存窗口内(默认 0ms)的
 * 并发请求会复用同一次真实查询,避免被刷接口时把目标服务器打爆。
 */
final class PlayerCountCache {

    private final getServerPlayerCount source;
    private final long cacheMs;
    private final long dedupeMs;
    private final boolean refreshOnRequest;
    private final Object lock = new Object();

    private PlayerCountResult last;
    private PlayerCountResult lastGood;
    private long lastAt;

    PlayerCountCache(getServerPlayerCount source, long cacheMs, long dedupeMs, boolean refreshOnRequest) {
        this.source = source;
        this.cacheMs = Math.max(0L, cacheMs);
        this.dedupeMs = Math.max(0L, dedupeMs);
        this.refreshOnRequest = refreshOnRequest;
    }

    /** 取一次人数(必要时真正查询)。 */
    PlayerCountResult get() {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            long window = refreshOnRequest
                    ? Math.max(cacheMs, dedupeMs)
                    : Math.max(Math.max(cacheMs, dedupeMs), 30_000L);
            if (last != null && now - lastAt < window) {
                return last;
            }
            if (!refreshOnRequest && last != null) {
                return last;
            }
            PlayerCountResult result = source.fetch();
            if (result.isOk()) {
                lastGood = result;
            }
            last = result;
            lastAt = System.currentTimeMillis();
            return result;
        }
    }

    /** 最近一次成功结果,从未成功过则为 null。 */
    PlayerCountResult getLastGood() {
        synchronized (lock) {
            return lastGood;
        }
    }

    /** 当前缓存是否仍在有效期内。 */
    boolean isFresh() {
        synchronized (lock) {
            long window = Math.max(cacheMs, dedupeMs);
            return last != null && System.currentTimeMillis() - lastAt < window;
        }
    }
}
