package com.github.tvbox.osc.bbox.dlna;

import org.fourthline.cling.binding.annotations.UpnpAction;
import org.fourthline.cling.binding.annotations.UpnpInputArgument;
import org.fourthline.cling.binding.annotations.UpnpOutputArgument;
import org.fourthline.cling.binding.annotations.UpnpService;
import org.fourthline.cling.binding.annotations.UpnpServiceId;
import org.fourthline.cling.binding.annotations.UpnpServiceType;
import org.fourthline.cling.binding.annotations.UpnpStateVariable;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;

/**
 * NOTE(2026-10-25 API15 port): UPnP AVTransport:1 接收端（DLNA 推流到电视）。
 * 写法依据 cling AnnotationActionBinder（master 源码核对）：
 *  - void 动作 + out @UpnpOutputArgument → cling 在方法执行后反射读状态变量字段现值返回；
 *  - 非 void 单 out → 返回值即 out；
 *  - 多 out（GetPositionInfo）→ void + 全部走状态变量字段（字段在方法体内现算）。
 * 事件：TransportState/TransportStatus/TransportPlaySpeed 为 evented，cling 自动发 GENA。
 */
@UpnpService(
        serviceId = @UpnpServiceId("AVTransport"),
        serviceType = @UpnpServiceType(value = "AVTransport", version = 1)
)
public class DlnaAVTransport {

    // ------------------------------------------------ evented 状态变量
    @UpnpStateVariable(defaultValue = "NO_MEDIA_PRESENT")
    private String TransportState = "NO_MEDIA_PRESENT";

    @UpnpStateVariable(defaultValue = "OK")
    private String TransportStatus = "OK";

    @UpnpStateVariable(defaultValue = "1", allowedValues = {"1"})
    private String TransportPlaySpeed = "1";

    // ------------------------------------------------ 媒体信息状态变量
    @UpnpStateVariable(defaultValue = "0")
    private UnsignedIntegerFourBytes NumberOfTracks = new UnsignedIntegerFourBytes(0);

    @UpnpStateVariable(defaultValue = "0")
    private UnsignedIntegerFourBytes CurrentTrack = new UnsignedIntegerFourBytes(0);

    @UpnpStateVariable(defaultValue = "00:00:00")
    private String CurrentTrackDuration = "00:00:00";

    @UpnpStateVariable(defaultValue = "00:00:00")
    private String CurrentMediaDuration = "00:00:00";

    @UpnpStateVariable(sendEvents = false)
    private String CurrentTrackURI = "";

    @UpnpStateVariable(sendEvents = false)
    private String CurrentTrackMetaData = "";

    @UpnpStateVariable(sendEvents = false)
    private String AVTransportURI = "";

    @UpnpStateVariable(sendEvents = false, defaultValue = "NOT_IMPLEMENTED")
    private String AVTransportURIMetaData = "NOT_IMPLEMENTED";

    @UpnpStateVariable(sendEvents = false, defaultValue = "NOT_IMPLEMENTED")
    private String NextAVTransportURI = "NOT_IMPLEMENTED";

    @UpnpStateVariable(sendEvents = false, defaultValue = "NOT_IMPLEMENTED")
    private String NextAVTransportURIMetaData = "NOT_IMPLEMENTED";

    @UpnpStateVariable(sendEvents = false, defaultValue = "NONE")
    private String PlayMedium = "NONE";

    @UpnpStateVariable(sendEvents = false, defaultValue = "NOT_IMPLEMENTED")
    private String RecordMedium = "NOT_IMPLEMENTED";

    @UpnpStateVariable(sendEvents = false, defaultValue = "NOT_IMPLEMENTED")
    private String WriteStatus = "NOT_IMPLEMENTED";

    // ------------------------------------------------ A_ARG_TYPE 状态变量
    // NOTE(2026-10-25): GetDeviceCapabilities/GetTransportSettings 的 out 参数必须有对应状态变量
    @UpnpStateVariable(sendEvents = false, defaultValue = "NETWORK")
    private String PossiblePlaybackStorageMedia = "NETWORK";

    @UpnpStateVariable(sendEvents = false, defaultValue = "NOT_IMPLEMENTED")
    private String PossibleRecordStorageMedia = "NOT_IMPLEMENTED";

    @UpnpStateVariable(sendEvents = false, defaultValue = "NOT_IMPLEMENTED")
    private String PossibleRecordQualityModes = "NOT_IMPLEMENTED";

    @UpnpStateVariable(sendEvents = false, defaultValue = "NORMAL")
    private String CurrentPlayMode = "NORMAL";

    @UpnpStateVariable(sendEvents = false, defaultValue = "NOT_IMPLEMENTED")
    private String CurrentRecordQualityMode = "NOT_IMPLEMENTED";

    @UpnpStateVariable(sendEvents = false, defaultValue = "0")
    private UnsignedIntegerFourBytes A_ARG_TYPE_InstanceID = new UnsignedIntegerFourBytes(0);

    @UpnpStateVariable(sendEvents = false, defaultValue = "REL_TIME",
            allowedValues = {"TRACK_NR", "ABS_TIME", "REL_TIME", "TRACK_SEGMENT"})
    private String A_ARG_TYPE_SeekMode = "REL_TIME";

    @UpnpStateVariable(sendEvents = false, defaultValue = "00:00:00")
    private String A_ARG_TYPE_SeekTarget = "00:00:00";

    @UpnpStateVariable(sendEvents = false, defaultValue = "00:00:00")
    private String RelativeTimePosition = "00:00:00";

    @UpnpStateVariable(sendEvents = false, defaultValue = "00:00:00")
    private String AbsoluteTimePosition = "00:00:00";

    @UpnpStateVariable(sendEvents = false, defaultValue = "2147483647")
    private Integer RelativeCounterPosition = 2147483647;

    @UpnpStateVariable(sendEvents = false, defaultValue = "2147483647")
    private Integer AbsoluteCounterPosition = 2147483647;

    // ------------------------------------------------ 动作

    @UpnpAction(name = "SetAVTransportURI")
    public void setAVTransportURI(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID") UnsignedIntegerFourBytes instanceId,
            @UpnpInputArgument(name = "CurrentURI", stateVariable = "AVTransportURI") String uri,
            @UpnpInputArgument(name = "CurrentURIMetaData", stateVariable = "AVTransportURIMetaData") String uriMetaData) {
        DlnaRenderState.reset();
        DlnaRenderState.currentUri = uri == null ? "" : uri;
        DlnaRenderState.uriMetaData = uriMetaData == null ? "" : uriMetaData;
        AVTransportURI = DlnaRenderState.currentUri;
        AVTransportURIMetaData = DlnaRenderState.uriMetaData.equals("") ? "NOT_IMPLEMENTED" : DlnaRenderState.uriMetaData;
        CurrentTrackURI = DlnaRenderState.currentUri;
        CurrentTrackMetaData = AVTransportURIMetaData;
        CurrentTrack = new UnsignedIntegerFourBytes(1);
        NumberOfTracks = new UnsignedIntegerFourBytes(1);
        TransportState = "TRANSITIONING";
        DlnaRenderState.TRANSPORT_STATE.set("TRANSITIONING");
        DlnaRendererService.notifyCast(DlnaRendererService.MSG_SET_URI);
    }

    @UpnpAction(name = "Play")
    public void play(@UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID") UnsignedIntegerFourBytes instanceId,
                     @UpnpInputArgument(name = "Speed", stateVariable = "TransportPlaySpeed") String speed) {
        if (speed != null && speed.length() > 0) {
            TransportPlaySpeed = speed;
            DlnaRenderState.speed = speed;
        }
        TransportState = "PLAYING";
        DlnaRenderState.TRANSPORT_STATE.set("PLAYING");
        DlnaRendererService.notifyCast(DlnaRendererService.MSG_PLAY);
    }

    @UpnpAction(name = "Pause")
    public void pause(@UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID") UnsignedIntegerFourBytes instanceId) {
        TransportState = "PAUSED_PLAYBACK";
        DlnaRenderState.TRANSPORT_STATE.set("PAUSED_PLAYBACK");
        DlnaRendererService.notifyCast(DlnaRendererService.MSG_PAUSE);
    }

    @UpnpAction(name = "Stop")
    public void stop(@UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID") UnsignedIntegerFourBytes instanceId) {
        TransportState = "STOPPED";
        DlnaRenderState.TRANSPORT_STATE.set("STOPPED");
        DlnaRendererService.notifyCast(DlnaRendererService.MSG_STOP);
    }

    @UpnpAction(name = "Seek")
    public void seek(@UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID") UnsignedIntegerFourBytes instanceId,
                     @UpnpInputArgument(name = "Unit", stateVariable = "A_ARG_TYPE_SeekMode") String unit,
                     @UpnpInputArgument(name = "Target", stateVariable = "A_ARG_TYPE_SeekTarget") String target) {
        if (target != null && target.contains(":")) {
            DlnaRendererService.seekTarget = target;
            DlnaRendererService.notifyCast(DlnaRendererService.MSG_SEEK);
        }
    }

    @UpnpAction(name = "GetTransportInfo", out = {
            @UpnpOutputArgument(name = "CurrentTransportState", stateVariable = "TransportState"),
            @UpnpOutputArgument(name = "CurrentTransportStatus", stateVariable = "TransportStatus"),
            @UpnpOutputArgument(name = "CurrentSpeed", stateVariable = "TransportPlaySpeed")
    })
    public void getTransportInfo(@UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID") UnsignedIntegerFourBytes instanceId) {
        // out 值由 cling 从状态变量字段反射读取（方法执行后）
    }

    @UpnpAction(name = "GetPositionInfo", out = {
            @UpnpOutputArgument(name = "Track", stateVariable = "CurrentTrack"),
            @UpnpOutputArgument(name = "TrackDuration", stateVariable = "CurrentTrackDuration"),
            @UpnpOutputArgument(name = "TrackMetaData", stateVariable = "CurrentTrackMetaData"),
            @UpnpOutputArgument(name = "TrackURI", stateVariable = "CurrentTrackURI"),
            @UpnpOutputArgument(name = "RelTime", stateVariable = "RelativeTimePosition"),
            @UpnpOutputArgument(name = "AbsTime", stateVariable = "AbsoluteTimePosition"),
            @UpnpOutputArgument(name = "RelCount", stateVariable = "RelativeCounterPosition"),
            @UpnpOutputArgument(name = "AbsCount", stateVariable = "AbsoluteCounterPosition")
    })
    public void getPositionInfo(@UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID") UnsignedIntegerFourBytes instanceId) {
        // 播放器侧每秒回写 DlnaRenderState.positionMs/durationMs，这里同步进字段供反射读取
        CurrentTrackDuration = DlnaRenderState.toHms(DlnaRenderState.durationMs);
        CurrentMediaDuration = CurrentTrackDuration;
        String pos = DlnaRenderState.toHms(DlnaRenderState.positionMs);
        RelativeTimePosition = pos;
        AbsoluteTimePosition = pos;
    }

    @UpnpAction(name = "GetMediaInfo", out = {
            @UpnpOutputArgument(name = "NrTracks", stateVariable = "NumberOfTracks"),
            @UpnpOutputArgument(name = "MediaDuration", stateVariable = "CurrentMediaDuration"),
            @UpnpOutputArgument(name = "CurrentURI", stateVariable = "AVTransportURI"),
            @UpnpOutputArgument(name = "CurrentURIMetaData", stateVariable = "AVTransportURIMetaData"),
            @UpnpOutputArgument(name = "NextAVTransportURI", stateVariable = "NextAVTransportURI"),
            @UpnpOutputArgument(name = "NextAVTransportURIMetaData", stateVariable = "NextAVTransportURIMetaData"),
            @UpnpOutputArgument(name = "PlayMedium", stateVariable = "PlayMedium"),
            @UpnpOutputArgument(name = "RecordMedium", stateVariable = "RecordMedium"),
            @UpnpOutputArgument(name = "WriteStatus", stateVariable = "WriteStatus")
    })
    public void getMediaInfo(@UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID") UnsignedIntegerFourBytes instanceId) {
        CurrentMediaDuration = DlnaRenderState.toHms(DlnaRenderState.durationMs);
    }

    @UpnpAction(name = "GetDeviceCapabilities", out = {
            @UpnpOutputArgument(name = "PlayMedia", getterName = "getPossiblePlaybackStorageMedia"),
            @UpnpOutputArgument(name = "RecMedia", getterName = "getPossibleRecordStorageMedia"),
            @UpnpOutputArgument(name = "RecQualityModes", getterName = "getPossibleRecordQualityModes")
    })
    public void getDeviceCapabilities(@UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID") UnsignedIntegerFourBytes instanceId) {
    }

    @UpnpAction(name = "GetTransportSettings", out = {
            @UpnpOutputArgument(name = "PlayMode", getterName = "getCurrentPlayMode"),
            @UpnpOutputArgument(name = "RecQualityMode", getterName = "getCurrentRecordQualityMode")
    })
    public void getTransportSettings(@UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID") UnsignedIntegerFourBytes instanceId) {
    }

    // ---- cling out 参数 getter（getterName 绑定）----
    public String getPossiblePlaybackStorageMedia() { return PossiblePlaybackStorageMedia; }
    public String getPossibleRecordStorageMedia() { return PossibleRecordStorageMedia; }
    public String getPossibleRecordQualityModes() { return PossibleRecordQualityModes; }
    public String getCurrentPlayMode() { return CurrentPlayMode; }
    public String getCurrentRecordQualityMode() { return CurrentRecordQualityMode; }

    /** TransportPlaySpeed 的合法值枚举（UPnP 标准仅 1） */
    public static class TransportPlaySpeedHolder {
    }
}
