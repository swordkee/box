package com.github.tvbox.osc.bbox.dlna;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import org.fourthline.cling.android.AndroidUpnpServiceConfiguration;
import org.fourthline.cling.binding.annotations.AnnotationLocalServiceBinder;
import org.fourthline.cling.model.DefaultServiceManager;
import org.fourthline.cling.model.meta.DeviceDetails;
import org.fourthline.cling.model.meta.DeviceIdentity;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.meta.ManufacturerDetails;
import org.fourthline.cling.model.meta.ModelDetails;
import org.fourthline.cling.model.types.DLNACaps;
import org.fourthline.cling.model.types.DLNADoc;
import org.fourthline.cling.model.types.UDADeviceType;
import org.fourthline.cling.model.types.UDN;
import org.fourthline.cling.support.connectionmanager.ConnectionManagerService;

import java.util.UUID;

/**
 * NOTE(2026-10-25 API15 port): DLNA MediaRenderer 承载服务（接收投屏）。
 * 设计借鉴 FongMi/TV 的 DLNARendererService（renderer/DMC 分离、状态单例），按 cling 2.1.2
 * 官方注解 API 自建（FongMi 用的 jupnp 需 API 21+，本机 API 15 用 cling——官方手册明确
 * Cling 2.x 最低要求 platform level 15）。multicast lock 由 AndroidUpnpServiceConfiguration 自管。
 */
public class DlnaRendererService extends Service {

    /** Cast 消息（EventBus 转发） */
    public static final int MSG_SET_URI = 1;
    public static final int MSG_PLAY = 2;
    public static final int MSG_PAUSE = 3;
    public static final int MSG_STOP = 4;
    public static final int MSG_SEEK = 5;
    /** MSG_SEEK 的目标（H:MM:SS） */
    public static volatile String seekTarget = "";

    private static volatile org.fourthline.cling.UpnpService upnpService;
    private static volatile boolean registered = false;

    @Override
    public void onCreate() {
        super.onCreate();
        if (upnpService != null) return;
        // NOTE(2026-10-25): cling/jetty 构造是重活（首次 jetty 静态初始化可达数秒），
        // 与 jar dexopt 并发时会拖死主进程（实测 ANR/进程死亡）——整体移后台线程
        new Thread(new Runnable() {
            @Override
            public void run() {
                initUpnp();
            }
        }, "DlnaInit").start();
    }

    private void initUpnp() {
        try {
            // NOTE(2026-10-25): 收 SSDP M-SEARCH 组播必需 MulticastLock——官方 AndroidUpnpServiceImpl
            // 的职责，我们自建 Service 绕过了它，必须显式获取（否则 M-SEARCH 零响应=手机发现但连不上）
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
            if (wm != null) {
                android.net.wifi.WifiManager.MulticastLock mlock = wm.createMulticastLock("tvbox-dlna");
                mlock.setReferenceCounted(false);
                mlock.acquire();
                LOG("multicast lock acquired");
            }
            // cling 内部日志用 JUL，桥到 logcat 否则全丢；开 FINE 看收包/响应细节
            java.util.logging.Logger rootLogger = java.util.logging.Logger.getLogger("");
            rootLogger.addHandler(new org.fourthline.cling.android.FixedAndroidLogHandler());
            rootLogger.setLevel(java.util.logging.Level.FINE);
            java.util.logging.Logger.getLogger("org.fourthline.cling").setLevel(java.util.logging.Level.FINE);
            // NOTE: 构造即启动（UpnpServiceImpl 构造内含 startup）
            AndroidUpnpServiceConfiguration cfg = new AndroidUpnpServiceConfiguration(0) {
                @Override
                public org.fourthline.cling.model.types.ServiceType[] getExclusiveServiceTypes() {
                    // 只关心这些服务类型，降低 registry 维护开销
                    return new org.fourthline.cling.model.types.ServiceType[]{
                            new org.fourthline.cling.model.types.UDAServiceType("AVTransport"),
                            new org.fourthline.cling.model.types.UDAServiceType("ConnectionManager")
                    };
                }
            };
            upnpService = new org.fourthline.cling.UpnpServiceImpl(cfg);
            registerRenderer();
            LOG("DLNA renderer started");
        } catch (Throwable th) {
            LOG("DLNA start failed: " + th);
            android.util.Log.e("TVBox-runtime", "DLNA start failed", th);
            upnpService = null;
        }
    }

    private synchronized void registerRenderer() {
        if (registered || upnpService == null) return;
        try {
            LocalService<DlnaAVTransport> avTransport =
                    new AnnotationLocalServiceBinder().read(DlnaAVTransport.class);
            avTransport.setManager(new DefaultServiceManager<DlnaAVTransport>(avTransport) {
                @Override
                protected DlnaAVTransport createServiceInstance() {
                    return new DlnaAVTransport();
                }
            });

            LocalService<ConnectionManagerService> connectionManager =
                    new AnnotationLocalServiceBinder().read(ConnectionManagerService.class);
            connectionManager.setManager(new DefaultServiceManager<ConnectionManagerService>(connectionManager) {
                @Override
                protected ConnectionManagerService createServiceInstance() {
                    return new ConnectionManagerService();
                }
            });

            UDN udn = new UDN(UUID.nameUUIDFromBytes("tvbox-bbox-dlna-renderer".getBytes("UTF-8")));
            String friendly = "TVBox (" + Build.MODEL + ")";
            LocalDevice device = new LocalDevice(
                    new DeviceIdentity(udn),
                    new UDADeviceType("MediaRenderer", 1),
                    new DeviceDetails(friendly,
                            new ManufacturerDetails("tvbox-bbox"),
                            new ModelDetails("TVBox MediaRenderer", "API15 DLNA renderer", "1.0"),
                            new DLNADoc[] { new DLNADoc("DMR", DLNADoc.Version.V1_5) },
                            new DLNACaps(new String[] {"DLNA.ORG_OP=01", "DLNA.ORG_CI=0"})),
                    new LocalService[]{avTransport, connectionManager});
            upnpService.getRegistry().addDevice(device);
            registered = true;
        } catch (Throwable th) {
            LOG("register renderer failed: " + th);
            android.util.Log.e("TVBox-runtime", "DLNA register failed", th);
        }
    }

    /** AVTransport 动作 → CastActivity（EventBus） */
    public static void notifyCast(int msg) {
        try {
            com.github.tvbox.osc.bbox.base.App.getInstance()
                    .sendCastMsg(new CastMsg(msg, seekTarget));
        } catch (Throwable ignored) {
        }
        if (msg == MSG_SET_URI) {
            // 从服务上下文拉起接收播放页
            try {
                android.content.Intent it = new android.content.Intent(
                        com.github.tvbox.osc.bbox.base.App.getInstance(), CastActivity.class);
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK |
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                com.github.tvbox.osc.bbox.base.App.getInstance().startActivity(it);
            } catch (Throwable th) {
                LOG("start CastActivity failed: " + th);
            }
        }
    }

    public static class CastMsg {
        public final int msg;
        public final String seekTarget;

        public CastMsg(int msg, String seekTarget) {
            this.msg = msg;
            this.seekTarget = seekTarget;
        }
    }

    private static void LOG(String s) {
        android.util.Log.i("TVBox-runtime", "DLNA " + s);
    }

    @Override
    public void onDestroy() {
        try {
            if (upnpService != null) upnpService.shutdown();
        } catch (Throwable ignored) {
        }
        upnpService = null;
        registered = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
