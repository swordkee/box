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
            // NOTE(2026-10-25): 9 源配置 JSON 全部内置 assets/configs/（启动释放 filesDir），
            // 切换零网络依赖——gh-proxy.org 公共代理不稳定，远程切换不可靠
            String[][] sources = {
                    {"本地内置", "file://tvbox.json"},
                    {"肥猫", "file://configs/feimao.json"},
                    {"王二小", "file://configs/wangerxiao.json"},
                    {"老刘备", "file://configs/laoliubei.json"},
                    {"小马", "file://configs/xiaoma.json"},
                    {"认歌", "file://configs/ouge.json"},
                    {"4K小盒子", "file://configs/4k.json"},
                    {"小盒子单仓", "file://configs/xiaohezi.json"},
                    {"拾光多仓", "file://configs/shiguang.json"},
                    {"挺好多仓", "file://configs/tinghao.json"},
            };
            for (String[] e : sources) {
                // 内置源以 APK 版本为准，无条件覆盖（旧 seed 是 gh-proxy 远程 URL）
                apiMap.put(e[0], e[1]);
                if (!apiNameHistory.contains(e[0])) apiNameHistory.add(e[0]);
            }
            for (String[] e : sources) {
                if (!apiMap.containsKey(e[0])) apiMap.put(e[0], e[1]);
                if (!apiNameHistory.contains(e[0])) apiNameHistory.add(e[0]);
            }
            Hawk.put(HawkConfig.API_MAP, apiMap);
            Hawk.put(HawkConfig.API_NAME_HISTORY, apiNameHistory);
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
