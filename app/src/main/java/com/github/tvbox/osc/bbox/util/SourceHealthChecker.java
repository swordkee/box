package com.github.tvbox.osc.bbox.util;

import com.github.tvbox.osc.bbox.bean.SourceBean;
import com.orhanobut.hawk.Hawk;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 源健康探测（2026-10-26 API15 port）
 *
 * 背景：实测「源本身不可播」是电视上最高频的白屏原因，且各源的失效形态完全不同，
 * 人工一个个试非常费时：
 *   - 牛牛/快车/丫丫/闪电/天涯：API 返回 **18 字节空响应**（HTTP 200 但无数据）
 *   - 无尽/索尼：**403**
 *   - 量子/非凡/暴风等：API 正常，但返回的是 share/xxx 网页链接（不可直拉播放）
 *   - 最大资源：API 与 m3u8 都正常，但**分段 CDN 只有 2.9KB/s**
 *
 * 本类用**搜索接口**做健康检查（不请求播放地址、不下整片），得到：
 *   连通性 + 延迟 + 响应体积 + 是否有有效数据
 *
 * ⚠️ 为什么必须用搜索接口：实测用列表接口 `ac=videolist&pg=1` 探测时 20 个源
 *   全部通过（HTTP 200、体积正常），完全没有判别力；失效只体现在搜索上
 *   （空壳响应 / 403）。换成 `ac=detail&wd=<关键字>` 后立刻分出 10 可用 / 10 失效。
 */
public class SourceHealthChecker {

    public static final String TAG = "SourceHealth";

    public static class Health {
        public boolean ok;       // 是否可用
        public long latencyMs;   // 响应耗时
        public int bytes;        // 响应体积
        public String note;      // 简短结论（给 UI 展示）
        public long checkedAt;   // 探测时间戳

        public Health(boolean ok, long latencyMs, int bytes, String note) {
            this.ok = ok;
            this.latencyMs = latencyMs;
            this.bytes = bytes;
            this.note = note;
            this.checkedAt = System.currentTimeMillis();
        }

        public boolean fresh(long ttlMs) {
            return System.currentTimeMillis() - checkedAt < ttlMs;
        }
    }

    private static final ConcurrentHashMap<String, Health> CACHE = new ConcurrentHashMap<>();
    private static final String HAWK_PREFIX = "src_health_";
    private static final long TTL_MS = 10 * 60 * 1000L;
    /** 探测用关键字：挑一个几乎必然有资源的词，避免把「没搜到」误判成「源失效」 */
    private static final String PROBE_KEYWORD = "小猪";

    private static ExecutorService pool;
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static OkHttpClient client;

    private static synchronized OkHttpClient client() {
        if (client == null) {
            client = OkGoHelper.getDefaultClient().newBuilder()
                    .connectTimeout(6, TimeUnit.SECONDS)
                    .readTimeout(6, TimeUnit.SECONDS)
                    .writeTimeout(6, TimeUnit.SECONDS)
                    .build();
        }
        return client;
    }

    /** 取缓存（先内存，再 Hawk；过期当作没有） */
    public static Health getCached(String sourceKey) {
        if (sourceKey == null) return null;
        Health h = CACHE.get(sourceKey);
        if (h != null && h.fresh(TTL_MS)) return h;
        try {
            h = Hawk.get(HAWK_PREFIX + sourceKey, (Health) null);
            if (h != null && h.fresh(TTL_MS)) {
                CACHE.put(sourceKey, h);
                return h;
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /** 给 UI 的一行后缀，如「320ms」「空响应」「403」；无缓存返回空串 */
    public static String label(String sourceKey) {
        Health h = getCached(sourceKey);
        if (h == null) return "";
        return h.ok ? h.note : (h.note + " ✗");
    }

    public interface OnResult {
        void onOne(SourceBean bean, Health health);

        void onDone();
    }

    /**
     * 后台探测一组站点。幂等：同一时间只跑一轮（避免反复按菜单键时叠加请求）。
     *
     * ⚠️ 内存/带宽约束（1GB 机型）：并发限 3、单个请求读满即关（不缓存 body）、
     * 超时 6s。20 个源最多占 3 条连接，不会和正在播放的拉流抢带宽太多。
     *
     * @param listener 每完成一个回调一次（回调在子线程，UI 更新请自行切主线程）
     */
    public static void checkAll(final java.util.List<SourceBean> list, final OnResult listener) {
        if (list == null || list.isEmpty()) return;
        if (!running.compareAndSet(false, true)) return;
        if (pool == null) pool = Executors.newFixedThreadPool(2);
        final int[] left = {list.size()};
        for (final SourceBean sb : list) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    Health h = null;
                    try {
                        h = check(sb);
                    } catch (Throwable th) {
                        LOG.e(TAG + " check fail " + sb.getName() + ": " + th);
                    }
                    if (h != null) {
                        CACHE.put(sb.getKey(), h);
                        try {
                            Hawk.put(HAWK_PREFIX + sb.getKey(), h);
                        } catch (Throwable ignore) {
                        }
                        if (listener != null) listener.onOne(sb, h);
                    }
                    synchronized (left) {
                        left[0]--;
                        if (left[0] <= 0) {
                            running.set(false);
                            if (listener != null) listener.onDone();
                        }
                    }
                }
            });
        }
    }

    /**
     * 探测单个站点。
     *
     * ⚠️ 关键教训（2026-10-26 实测）：**必须用搜索接口探测，不能用列表接口**。
     *   用 `ac=videolist&pg=1`（列表）测，20 个源全部通过（HTTP 200 + 体积正常），
     *   完全没有判别力；而失效恰恰只体现在**搜索**上：
     *     - 牛牛/快车/丫丫/闪电/天涯/茅台/新浪 → 搜索返回 6~57 字节空壳（"空响应"）
     *     - 无尽/索尼 → 403
     *   换成 `ac=detail&wd=<关键字>` 后，立刻分出 10 可用 / 10 失效，与人工实测一致。
     *
     * 判据：
     *   - 非 2xx              → 「HTTP403」
     *   - 体积 < 200 字节     → 「空响应」
     *   - 无 "vod_id"/"list"  → 「格式异常」
     *   - 命中 0 条           → 「搜不到」（仍算可用，只是这个词没资源）
     *   - 耗时 > 5s           → 「超时」；> 2.5s → 「慢Ns」
     *   - 正常                → 「NNNms」
     *
     * jar 类型（type==3）不做 HTTP 探测：可用性取决于 jar 实现。
     */
    public static Health check(SourceBean sb) {
        if (sb == null) return null;
        String api = sb.getApi();
        if (api == null || api.isEmpty()) {
            return new Health(false, 0, 0, "无API");
        }
        if (sb.getType() == 3) {
            return new Health(true, 0, 0, "jar源");
        }
        String url;
        // 用固定关键字探测搜索接口（比列表页有判别力，见上方说明）
        String wd;
        try {
            wd = java.net.URLEncoder.encode(PROBE_KEYWORD, "UTF-8");
        } catch (Throwable th) {
            wd = PROBE_KEYWORD;
        }
        if (sb.getType() == 4) {
            url = api + (api.contains("?") ? "&" : "?") + "ac=detail&quick=true&wd=" + wd;
        } else {
            url = api + (api.contains("?") ? "&" : "?") + "ac=detail&wd=" + wd;
        }
        long t0 = System.currentTimeMillis();
        Response resp = null;
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 4.0.4) AppleWebKit/537.36")
                    .build();
            resp = client().newCall(req).execute();
            int code = resp.code();
            long cost = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                return new Health(false, cost, 0, "HTTP" + code);
            }
            String body = resp.body() == null ? "" : resp.body().string();
            int len = body.length();
            if (len < 200) {
                // 牛牛/快车/丫丫/闪电/天涯/茅台/新浪 就是这种：HTTP 200 但只有几个字节
                return new Health(false, cost, len, "空响应");
            }
            if (!body.contains("\"vod_id\"") && !body.contains("\"list\"")) {
                return new Health(false, cost, len, "格式异常");
            }
            if (cost > 5000) {
                return new Health(false, cost, len, "超时");
            }
            // 搜不到内容不算失效：接口通了就算可用（关键字恰好没资源而已）
            boolean noHit = !body.contains("\"vod_id\"");
            if (noHit) {
                return new Health(true, cost, len, "无此片");
            }
            if (cost > 2500) {
                return new Health(true, cost, len, "慢" + (cost / 1000) + "s");
            }
            return new Health(true, cost, len, cost + "ms");
        } catch (Throwable th) {
            long cost = System.currentTimeMillis() - t0;
            String msg = String.valueOf(th.getMessage()).toLowerCase();
            if (msg.contains("timeout") || msg.contains("timed out")) {
                return new Health(false, cost, 0, "超时");
            }
            if (msg.contains("403")) return new Health(false, cost, 0, "403");
            if (msg.contains("404")) return new Health(false, cost, 0, "404");
            if (msg.contains("unable to resolve") || msg.contains("unknownhost")) {
                return new Health(false, cost, 0, "DNS失败");
            }
            return new Health(false, cost, 0, "失败");
        } finally {
            try {
                if (resp != null) resp.close();
            } catch (Throwable ignore) {
            }
        }
    }

    public static void clearAll() {
        CACHE.clear();
    }
}
