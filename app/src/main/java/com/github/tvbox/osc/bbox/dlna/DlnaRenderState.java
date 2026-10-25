package com.github.tvbox.osc.bbox.dlna;

import java.util.concurrent.atomic.AtomicReference;

/**
 * NOTE(2026-10-25 API15 port): DLNA Renderer 全局播放状态（借鉴 FongMi/TV 的 RenderState 分层设计）。
 * AVTransport LocalService 的 cling 状态变量从这里读，CastActivity 播放时往这里写。
 * 全部 static：单进程内唯一，cling 反射读实例字段时 static 亦合法。
 */
public class DlnaRenderState {

    /** UPnP TransportState 合法值：STOPPED / PLAYING / PAUSED_PLAYBACK / TRANSITIONING / NO_MEDIA_PRESENT */
    public static final AtomicReference<String> TRANSPORT_STATE = new AtomicReference<>("NO_MEDIA_PRESENT");
    /** UPnP TransportStatus：OK / ERROR_OCCURRED */
    public static final AtomicReference<String> TRANSPORT_STATUS = new AtomicReference<>("OK");
    /** 当前媒体 URI */
    public static volatile String currentUri = "";
    /** 元数据（拿到什么透传什么，通常为空） */
    public static volatile String uriMetaData = "";
    /** 播放位置（ms），CastActivity 播放中定时回写 */
    public static volatile long positionMs = 0;
    /** 媒体时长（ms），拿到 duration 后回写 */
    public static volatile long durationMs = 0;
    /** 播放速度（GetPositionInfo 用） */
    public static volatile String speed = "1";

    public static void reset() {
        TRANSPORT_STATE.set("NO_MEDIA_PRESENT");
        TRANSPORT_STATUS.set("OK");
        currentUri = "";
        uriMetaData = "";
        positionMs = 0;
        durationMs = 0;
    }

    /** 秒 → UPnP H:MM:SS.FFD 时长格式 */
    public static String toHms(long ms) {
        if (ms <= 0) return "00:00:00";
        long total = ms / 1000;
        long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }
}
