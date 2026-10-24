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

        putDefault(HawkConfig.DEBUG_OPEN, false);
        putDefault(HawkConfig.PLAY_TYPE, 0); //硬解优先（系统 MediaPlayer=OMX 硬解）；isVideo getTrackInfo 崩溃已修；失败时 autoRetry 自动回退 ijk 软解
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
            String[][] sources = {
                    {"本地内置", "file://tvbox.json"},
                    {"肥猫", "https://gh-proxy.org/https://raw.githubusercontent.com/Lightconer/tvbox-ysc-config/main/output/feimao.json"},
                    {"王二小", "https://gh-proxy.org/https://raw.githubusercontent.com/Lightconer/tvbox-ysc-config/main/output/wangerxiao.json"},
                    {"老刘备", "https://gh-proxy.org/https://raw.githubusercontent.com/Lightconer/tvbox-ysc-config/main/output/laoliubei.json"},
                    {"小马", "https://gh-proxy.org/https://raw.githubusercontent.com/Lightconer/tvbox-ysc-config/main/output/xiaoma.json"},
                    {"认歌", "https://gh-proxy.org/https://raw.githubusercontent.com/Lightconer/tvbox-ysc-config/main/output/ouge.json"},
                    {"4K小盒子", "https://gh-proxy.org/https://raw.githubusercontent.com/Lightconer/tvbox-ysc-config/main/output/4k.json"},
                    {"小盒子单仓", "https://gh-proxy.org/https://raw.githubusercontent.com/Lightconer/tvbox-ysc-config/main/output/xiaohezi.json"},
                    {"拾光多仓", "https://gh-proxy.org/https://raw.githubusercontent.com/Lightconer/tvbox-ysc-config/main/output/shiguang.json"},
                    {"挺好多仓", "https://gh-proxy.org/https://raw.githubusercontent.com/Lightconer/tvbox-ysc-config/main/output/tinghao.json"},
            };
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
