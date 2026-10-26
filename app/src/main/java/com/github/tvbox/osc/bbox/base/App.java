package com.github.tvbox.osc.bbox.base;

import android.app.Activity;
import android.widget.Toast;
import androidx.multidex.MultiDexApplication;
import com.github.catvod.crawler.JsLoader;
import com.github.tvbox.osc.bbox.bean.VodInfo;
import com.github.tvbox.osc.bbox.callback.EmptyCallback;
import com.github.tvbox.osc.bbox.callback.LoadingCallback;
import com.github.tvbox.osc.bbox.constant.URL;
import com.github.tvbox.osc.bbox.data.AppDataManager;
import com.github.tvbox.osc.bbox.server.ControlManager;
import com.github.tvbox.osc.bbox.util.*;
import com.kingja.loadsir.core.LoadSir;
import com.orhanobut.hawk.Hawk;
import com.p2p.P2PClass;
import com.whl.quickjs.android.QuickJSLoader;
import com.xuexiang.xupdate.XUpdate;
import com.xuexiang.xupdate.entity.UpdateError;
import com.xuexiang.xupdate.listener.OnUpdateFailureListener;
import com.xuexiang.xupdate.utils.UpdateUtils;
import me.jessyan.autosize.AutoSizeConfig;
import me.jessyan.autosize.unit.Subunits;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static com.xuexiang.xupdate.entity.UpdateError.ERROR.CHECK_NO_NEW_VERSION;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public class App extends MultiDexApplication {
    // ^(?!.*(HWComposer|TrafficController|lowmemorykiller)).*$

    private static App instance;

    private static P2PClass p;
    public static String burl;
    private static String dashData;

    @Override
    public void onCreate() {
        super.onCreate();
        // NOTE(API15 port): do NOT insert Conscrypt here — this SoC (MT5880, Cortex-A9) has NO NEON,
        // conscrypt's BoringSSL armeabi-v7a build SIGILLs in SSL_CTX_new (tombstone evidence 2026-10-24).
        // TLS1.2 availability is the system JSSE's call; SSLSocketFactoryCompat already enables
        // every supported non-SSL protocol per socket.
        instance = this;
        initParams();
        // OKGo
        OkGoHelper.init(); // 台标获取
        EpgUtil.init();
        // 初始化Web服务器
        ControlManager.init(this);
        LOG.i("Web服务器初始化完成！");
        // 初始化数据库
        AppDataManager.init();
        LoadSir.beginBuilder()
                .addCallback(new EmptyCallback())
                .addCallback(new LoadingCallback())
                .commit();
        AutoSizeConfig.getInstance().setCustomFragment(true).getUnitsManager()
                .setSupportDP(false)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM);
        PlayerHelper.init();
        QuickJSLoader.init();
        FileUtils.cleanPlayerCache();
        initXUpdate();

        // davTest();
    }

    // private void davTest () {
    //     String davUrl = "https://www.bunnyabc.eu.org:15245/dav/";
    //     String uname = "tvbox_dav";
    //     String password = "123456";
    //
    //     OkHttpSardine client = new OkHttpSardine();
    //     client.setCredentials(uname, password);
    //
    //     try {
    //         InputStream inputStream = client.get("https://www.bunnyabc.eu.org:15245/dav/test1.txt");
    //
    //     } catch (IOException e) {
    //         throw new RuntimeException(e);
    //     }
    // }

    private void initXUpdate() {
        XUpdate.get()
                .debug(true)
                // 默认设置只在wifi下检查版本更新
                .isWifiOnly(false)
                // 默认设置使用get请求检查版本
                .isGet(true)
                // 默认设置非自动模式，可根据具体使用配置
                .isAutoMode(false)
                // 设置默认公共请求参数
                .param("versionCode", UpdateUtils.getVersionCode(this))
                .param("appKey", getPackageName())
                // 设置版本更新出错的监听
                .setOnUpdateFailureListener(new OnUpdateFailureListener() {
                    @Override
                    public void onFailure(UpdateError error) {
                        error.printStackTrace();
                        // 对不同错误进行处理
                        if (error.getCode() != CHECK_NO_NEW_VERSION) {
                            Toast.makeText(getApplicationContext(), error.toString(), Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                // 设置是否支持静默安装，默认是true
                .supportSilentInstall(false)
                // 这个必须设置！实现网络请求功能。
                // .setIUpdateHttpService(new OkGoUpdateHttpService())
                // 这个必须初始化
                .init(this);
    }

    private void initParams() {
        // Hawk
        Hawk.init(this).build();

        initBundledConfig();
        // MITM proxy for jar https
        try {
            com.github.tvbox.osc.bbox.util.MitmCertManager.init(this);
            if (com.github.tvbox.osc.bbox.util.MitmProxy.start()) {
                java.net.ProxySelector ps = new java.net.ProxySelector() {
                    public java.util.List<java.net.Proxy> select(java.net.URI uri) {
                        String h = uri == null ? "" : uri.getHost();
                        if (h != null && (h.equals("127.0.0.1") || h.equals("localhost")))
                            return java.util.Collections.singletonList(java.net.Proxy.NO_PROXY);
                        return java.util.Collections.singletonList(new java.net.Proxy(
                                java.net.Proxy.Type.HTTP,
                                java.net.InetSocketAddress.createUnresolved("127.0.0.1", 8888)));
                    }
                    public void connectFailed(java.net.URI u, java.net.SocketAddress sa, java.io.IOException ioe) {}
                };
                java.net.ProxySelector.setDefault(ps);
                LOG.i("MITM proxy wired");
            }
        } catch (Throwable th) {
            LOG.e("MITM init error: " + th);
        }

        putDefault(HawkConfig.DEBUG_OPEN, false);
        // NOTE(2026-10-25): 硬解优先（系统 CmpbPlayer OMX），https 流由 m3u8 反代改写为本地 http 喂流；
        // ijk 软解在此 CPU 上卡成 PPT，仅作硬解失败回退。老安装 Hawk 已存 1，用哨兵键做一次性迁移。
        if (!Hawk.contains("play_type_hw_migrated_v3")) {
            // NOTE(2026-10-25 实测裁决): ijk 软解在此 GPU 上色差+顿卡，exo 需 MediaCodec(API16+)，
            // 只有系统 CmpbPlayer 硬解可用——强制全局默认系统播放器，内置源 playerType 已统一 -1
            Hawk.put(HawkConfig.PLAY_TYPE, 0);
            // M3U8_PURIFY=false 走 goPlayUrl 直连，无 proxify，硬解拉不动 https → 强制走 purify/proxify 路径
            Hawk.put(HawkConfig.M3U8_PURIFY, true);
            Hawk.put("play_type_hw_migrated_v3", true);
            LOG.i("echo-play-type migrated v3: hw-decode-only default");
        }
        putDefault(HawkConfig.HOME_REC, 1);
        // 默认渲染方式：推荐手机使用0-texture，电视1-surface
        putDefault(HawkConfig.PLAY_RENDER, 1);
        putDefault(HawkConfig.IJK_CODEC, "硬解码");
        putDefault(HawkConfig.HOME_REC_STYLE, false);// 首页多行

        putDefault(HawkConfig.PROXY_URL, URL.DOMAIN_NAME_PROXY);
        // 默认换台反转
        putDefault(HawkConfig.LIVE_CHANNEL_REVERSE, true);
        // 默认显示时间
        putDefault(HawkConfig.LIVE_SHOW_TIME, true);
        putDefaultApis();

        // NOTE(2026-10-24 API15 port): 预置多仓订阅子源到配置切换列表（设置->配置）
        try {
            HashMap<String, String> apiMap = Hawk.get(HawkConfig.API_MAP, new HashMap<String, String>());
            ArrayList<String> apiNameHistory = Hawk.get(HawkConfig.API_NAME_HISTORY, new ArrayList<String>());
            // NOTE(2026-10-25): 自愈——已移除的崩溃源（dexopt SIGSEGV）残留在 Hawk 时重置回本地内置
        try {
            String cur = Hawk.get(HawkConfig.API_URL, "");
            // 所有内置 configs/*.json 的真实存在性校验——指向不存在的文件就重置（防止残留已删源）
            boolean needReset = false;
            if (cur.startsWith("file://configs/")) {
                String name = cur.substring("file://configs/".length());
                java.io.File f = new java.io.File(new java.io.File(getFilesDir(), "configs"), name);
                needReset = !f.exists();
            }
            // 王二小 jar 的 csp 类 JNI 在 4.0.4 上 native 崩溃（tombstone 实证），强制迁移
            if (cur.contains("wangerxiao")) {
                needReset = true;
            }
            if (needReset) {
                Hawk.put(HawkConfig.API_URL, "file://tvbox.json");
                Hawk.put(HawkConfig.API_NAME, "本地内置");
                LOG.i("echo-config self-heal: reset to bundled tvbox.json (missing source " + cur + ")");
            }
            // 同时清掉 API_MAP 里这三项，避免列表再选到
            // NOTE(2026-10-25): 白名单重建——API_MAP/历史里凡不属于当前内置源的一律清掉。
            // 之前只删了固定几个名字，但 Hawk 里可能残留别的已删源（如 肥猫/老刘备/4K小盒子），
            // 导致换源弹框照样列出不可用的源（用户实测『源与分类乱出现』）。
            final java.util.HashSet<String> allow = new java.util.HashSet<String>();
            allow.add("本地内置");
            allow.add("纯API合集");
            allow.add("认歌");
            allow.add("小盒子");
            allow.add("小马");

            HashMap<String, String> healMap = Hawk.get(HawkConfig.API_MAP, new HashMap<String, String>());
            ArrayList<String> healHistory = Hawk.get(HawkConfig.API_NAME_HISTORY, new ArrayList<String>());
            boolean dirty = false;
            java.util.Iterator<String> it = healMap.keySet().iterator();
            while (it.hasNext()) {
                String k = it.next();
                if (!allow.contains(k)) {
                    it.remove();
                    dirty = true;
                    LOG.i("echo-config self-heal: pruned source " + k);
                }
            }
            for (int i = healHistory.size() - 1; i >= 0; i--) {
                if (!allow.contains(healHistory.get(i))) {
                    LOG.i("echo-config self-heal: pruned history " + healHistory.get(i));
                    healHistory.remove(i);
                    dirty = true;
                }
            }
            if (dirty) {
                Hawk.put(HawkConfig.API_MAP, healMap);
                Hawk.put(HawkConfig.API_NAME_HISTORY, healHistory);
                LOG.i("echo-config self-heal: map pruned to whitelist");
            }
        } catch (Throwable th) {
            LOG.e("config self-heal: " + th);
        }

        // NOTE(2026-10-25): 4 源内置（ouge/wangerxiao/xiaohezi/xiaoma），jar 全部打进 APK（assets/jars/）
        // 已移除 老刘备/拾光/挺好/4K小盒子/肥猫——它们的 jar 在 dalvik dexopt 时 native crash(signal 11)
        // 或远程 jar 已失效（404/超时），只保留实测可用的 4 个
            // NOTE(2026-10-25): 只保留本地内置源（量子/非凡/暴风，纯 JSON API）。
            // 所有 jar 聚合源已移除——dalvik 4.0.4 加载这些 jar 时虚拟机自身 SIGSEGV
            // （fault addr deadd00d，Java 层无解），实测 ouge/wangerxiao/xiaohezi/xiaoma 全崩
            // NOTE(2026-10-25): 只保留纯 API 源（零 jar 依赖，规避 dalvik dexopt 崩溃）
            // 本地内置=量子/非凡/暴风；纯API合集=10 个苹果CMS站点（电影天堂/天涯/量子/非凡/极速/建安/百度/暴风/闪电/光速）
            // NOTE(2026-10-26): 双轨源——纯 API（20 个，零 jar 依赖）+ jar 源 3 个。
            // 王二小(wangerxiao.jar) 已移除：其 csp 类 JNI 调用在 4.0.4 上 native 崩溃
            // （tombstone: #02 pc in cache/.wexfnw* + dvmPlatformInvoke，fault addr 0）
            String[][] sources = {
                    {"本地内置", "file://tvbox.json"},
                    {"纯API合集", "file://configs/api_only.json"},
                    {"认歌", "file://configs/ouge.json"},
                    {"小盒子", "file://configs/xiaohezi.json"},
                    {"小马", "file://configs/xiaoma.json"},
            };
            HashMap<String, String> seedMap = Hawk.get(HawkConfig.API_MAP, new HashMap<String, String>());
            ArrayList<String> seedHistory = Hawk.get(HawkConfig.API_NAME_HISTORY, new ArrayList<String>());
            for (String[] e : sources) {
                // 内置源以 APK 版本为准，无条件覆盖
                seedMap.put(e[0], e[1]);
                if (!seedHistory.contains(e[0])) seedHistory.add(e[0]);
            }
            Hawk.put(HawkConfig.API_MAP, seedMap);
            Hawk.put(HawkConfig.API_NAME_HISTORY, seedHistory);
        } catch (Throwable th) {
            LOG.e("seed api map: " + th);
        }

        /*
        if (Hawk.contains(HawkConfig.AVAILABLE_PROXY_URL)) {
            Checker.getInstance().checkProxy(isAvailable -> {
                if (!isAvailable) {
                    LOG.i("代理链接失效，删除缓存");
                    Hawk.delete(HawkConfig.AVAILABLE_PROXY_URL);
                    Checker.getInstance().getAllProxyUrls(url -> {
                        if (!Hawk.contains(HawkConfig.AVAILABLE_PROXY_URL)) {
                            LOG.i("更新代理链接" + url + "写入缓存");
                            Hawk.put(HawkConfig.AVAILABLE_PROXY_URL, url);
                            putDefaultApis(url);
                        }
                    });
                }
                else {
                    LOG.i("代理链接依旧可用");
                }
            }, Hawk.get(HawkConfig.AVAILABLE_PROXY_URL, ""));
        }
        else {
            Checker.getInstance().getAllProxyUrls(url -> {
                if (!Hawk.contains(HawkConfig.AVAILABLE_PROXY_URL)) {
                    LOG.i("将代理链接" + url + "写入缓存");
                    Hawk.put(HawkConfig.AVAILABLE_PROXY_URL, url);
                    putDefaultApis(url);
                }
            });
        }
        */
    }

    private void putDefaultApis() {
        String url = URL.DOMAIN_NAME_PROXY;

        // 默认加速历史记录
        List<String> proxyUrlHistory = Hawk.get(HawkConfig.PROXY_URL_HISTORY, new ArrayList<>());
        proxyUrlHistory.add(url);
        proxyUrlHistory.add("https://ghproxy.net/");
        proxyUrlHistory.add("https://mirror.ghproxy.com/");
        // 默认仓库地址
        String defaultStoreApi = URL.DEFAULT_STORE_API_URL;

        // 添加默认epg历史记录
        List<String> epgHistory = Hawk.get(HawkConfig.EPG_HISTORY, new ArrayList<>());
        epgHistory.add("https://epg.112114.xyz?ch={name}&date={date}");
        epgHistory.add("https://epg.51zmt.top:8001/?ch={name}&date={date}");
        // 默认epg
        String defaultEpgUrl = "https://epg.51zmt.top:8001?ch={name}&date={date}"; //BC TLS 栈接管后恢复 https EPG


        // 不添加默认线路
        // 默认线路地址
        // String defaultApiName = "自备份线路";
        // String defaultApi = url + URL.DEFAULT_API_URL;
        // Map<String, String> defaultApiMap = Hawk.get(HawkConfig.API_MAP, new HashMap<>());
        // defaultApiMap.put(defaultApiName, defaultApi);
        // List<String> defaultApiHistory = Hawk.get(HawkConfig.API_NAME_HISTORY, new ArrayList<>());
        // defaultApiHistory.add(defaultApiName);
        // putDefault(HawkConfig.API_URL, defaultApi);
        // putDefault(HawkConfig.API_NAME, defaultApiName);
        // putDefault(HawkConfig.API_NAME_HISTORY, defaultApiHistory);
        // putDefault(HawkConfig.API_MAP, defaultApiMap);
        // 添加默认直播源
        putDefault(HawkConfig.LIVE_URL, URL.DEFAULT_LIVE_URL);

        putDefault(HawkConfig.DEFAULT_STORE_API, defaultStoreApi);
        putDefault(HawkConfig.PROXY_URL_HISTORY, proxyUrlHistory);
        putDefault(HawkConfig.EPG_URL, defaultEpgUrl);
        putDefault(HawkConfig.EPG_HISTORY, epgHistory);
        putDefault(HawkConfig.LIVE_SHOW_NET_SPEED, true);
        putDefault(HawkConfig.IS_GLOBAL_SCALE, true);
    }

    private void putDefault(String key, Object value) {
        if (!Hawk.contains(key)) {
            Hawk.put(key, value);
        }
    }

    // NOTE(2026-10-24 API15 port): 内置配置——把 asset 中的 tvbox.json 释放到私有目录，
    // 配合 RemoteServer /file/ 的 filesDir 回退，电视重启清空 /mnt/sdcard 后仍能自主加载
    private void initBundledConfig() {
        try {
            java.io.InputStream is = getAssets().open("tvbox.json");
            java.io.File dst = new java.io.File(getFilesDir(), "tvbox.json");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(dst, false);
            byte[] tmp = new byte[8192];
            int n;
            while ((n = is.read(tmp)) != -1) fos.write(tmp, 0, n);
            fos.close();
            is.close();
            LOG.i("echo-bundled-config released");
        } catch (Throwable th) {
            LOG.i("echo-bundled-config failed " + th);
        }
        // NOTE(2026-10-25): 释放内置多仓源配置 assets/configs/*.json 到 filesDir/configs/
        // （/file/ 端点 sdcard 缺失时回退 filesDir 同路径，电视重启清 sdcard 后切换列表仍可用）
        try {
            String[] cfgs = getAssets().list("configs");
            if (cfgs != null && cfgs.length > 0) {
                java.io.File dir = new java.io.File(getFilesDir(), "configs");
                dir.mkdirs();
                for (String c : cfgs) {
                    java.io.InputStream cis = getAssets().open("configs/" + c);
                    java.io.FileOutputStream cfos = new java.io.FileOutputStream(new java.io.File(dir, c), false);
                    byte[] tmp2 = new byte[8192];
                    int n2;
                    while ((n2 = cis.read(tmp2)) != -1) cfos.write(tmp2, 0, n2);
                    cfos.close();
                    cis.close();
                }
                LOG.i("echo-bundled-configs released " + cfgs.length);
            }
        } catch (Throwable th) {
            LOG.i("echo-bundled-configs failed " + th);
        }
        // NOTE(2026-10-25): 内置 jar 释放——相对路径 spider（./ztha.jar 等）内置成 file:// 配置后
        // 基准变了会 404；按 loadJar 的 cache key（MD5(spider 相对串)）预置到 filesDir/csp，
        // 配置 md5 与 jar 实际 md5 一致 → 缓存命中直接用，零网络
        try {
            // NOTE(2026-10-25): 内置 jar 释放——实测可用的 jar 打进 APK（assets/jars/），
            // 释放到 filesDir/jars/，配置用 file://jars/xxx.jar（RemoteServer /file/ 读 filesDir），彻底零网络
            // NOTE(2026-10-26): 移除 wangerxiao.jar —— 其 csp 类 JNI 在 4.0.4 native 崩溃
            // （tombstone: dvmPlatformInvoke / fault addr 0），配置已不再引用，继续打包只是白占体积。
            String[] bundledJars = {"ouge.jar", "xiaohezi.jar", "xiaoma.jar"};
            java.io.File jarDir = new java.io.File(getFilesDir(), "jars");
            jarDir.mkdirs();
            // 清掉历史版本残留的 wangerxiao.jar，避免"文件还在但配置已删"的混淆
            try {
                java.io.File stale = new java.io.File(jarDir, "wangerxiao.jar");
                if (stale.exists()) stale.delete();
            } catch (Throwable ignore) {
            }
            for (String jarName : bundledJars) {
                try {
                    java.io.InputStream jis = getAssets().open("jars/" + jarName);
                    java.io.FileOutputStream jfos = new java.io.FileOutputStream(
                            new java.io.File(jarDir, jarName), false);
                    byte[] jtmp = new byte[8192];
                    int jn;
                    while ((jn = jis.read(jtmp)) != -1) jfos.write(jtmp, 0, jn);
                    jfos.close();
                    jis.close();
                } catch (Throwable th) {
                    LOG.i("echo-bundled-jar miss " + jarName + ": " + th);
                }
            }
            LOG.i("echo-bundled-jars released");
        } catch (Throwable th) {
            LOG.i("echo-bundled-jars failed " + th);
        }
    }

    /** DLNA: AVTransport 动作 → CastActivity（greenrobot EventBus） */
    public void sendCastMsg(com.github.tvbox.osc.bbox.dlna.DlnaRendererService.CastMsg msg) {
        org.greenrobot.eventbus.EventBus.getDefault().post(msg);
    }

    public static App getInstance() {
        return instance;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        JsLoader.destroy();
    }


    private VodInfo vodInfo;
    public void setVodInfo(VodInfo vodinfo){
        this.vodInfo = vodinfo;
    }
    public VodInfo getVodInfo(){
        return this.vodInfo;
    }

    public static P2PClass getp2p() {
        try {
            if (p == null) {
                p = new P2PClass(FileUtils.getExternalCachePath());
            }
            return p;
        } catch (Exception e) {
            LOG.e(e.toString());
            return null;
        }
    }

    public Activity getCurrentActivity() {
        return AppManager.getInstance().currentActivity();
    }

    public void setDashData(String data) {
        dashData = data;
    }
    public String getDashData() {
        return dashData;
    }
}
