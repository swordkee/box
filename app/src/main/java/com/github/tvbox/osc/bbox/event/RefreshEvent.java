package com.github.tvbox.osc.bbox.event;

/**
 * @author pj567
 * @date :2021/1/6
 * @description:
 */
public class RefreshEvent {
    public static final int TYPE_REFRESH = 0;
    public static final int TYPE_HISTORY_REFRESH = 1;
    public static final int TYPE_QUICK_SEARCH = 2;
    public static final int TYPE_QUICK_SEARCH_SELECT = 3;
    public static final int TYPE_QUICK_SEARCH_WORD = 4;
    public static final int TYPE_QUICK_SEARCH_WORD_CHANGE = 5;
    public static final int TYPE_SEARCH_RESULT = 6;
    public static final int TYPE_QUICK_SEARCH_RESULT = 7;
    public static final int TYPE_API_URL_CHANGE = 8;
    public static final int TYPE_PUSH_URL = 9;
    public static final int TYPE_EPG_URL_CHANGE = 10;
    public static final int TYPE_SETTING_SEARCH_TV = 11;
    public static final int TYPE_SUBTITLE_SIZE_CHANGE = 12;
    public static final int TYPE_FILTER_CHANGE = 13;
    public static final int TYPE_STORE_CONFIG_CHANGE = 14;
    public static final int TYPE_APP_REFRESH = 15;
    public static final int TYPE_API_LIVE_URL = 16;
    public static final int TYPE_API_EPG_URL = 17;
    public static final int TYPE_PROXY_URL = 18;
    // NOTE(2026-10-26 API15 port): 播放页本源彻底失败（重试+切播放器都不行）时发出，
    // 详情页收到后自动打开「快速搜索」让用户换源。点播原本没有跨源自动换源
    // （直播有），实测「源本身不可播」是最高频的白屏原因（百度 JPEG 假 HLS、
    // 最大资源 CDN 2.9KB/s），靠自己重试永远不会成功。
    public static final int TYPE_PLAY_FAILED = 19;
    public int type;
    public Object obj;

    public RefreshEvent(int type) {
        this.type = type;
    }

    public RefreshEvent(int type, Object obj) {
        this.type = type;
        this.obj = obj;
    }
}