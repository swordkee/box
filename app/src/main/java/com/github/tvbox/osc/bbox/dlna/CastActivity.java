package com.github.tvbox.osc.bbox.dlna;

import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.osc.bbox.R;
import com.github.tvbox.osc.bbox.base.BaseActivity;
import com.github.tvbox.osc.bbox.player.MyVideoView;
import com.github.tvbox.osc.bbox.util.PlayerHelper;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * NOTE(2026-10-25 API15 port): DLNA 接收播放页（电视作为 MediaRenderer）。
 * 手机/电脑推 SetAVTransportURI 后由 DlnaRendererService 拉起本页；
 * Play/Pause/Stop/Seek 经 EventBus(CastMsg) 驱动；位置每秒回写 DlnaRenderState 供 GetPositionInfo。
 * 播放器跟随全局默认（系统硬解优先），轮询到 STATE_ERROR 后回退 ijk 重试一次。
 */
public class CastActivity extends BaseActivity {

    private MyVideoView mVideoView;
    private TextView tvTitle;
    private final Handler mHandler = new Handler();
    private boolean retriedWithIjk = false;
    private String currentUrl = "";

    private final Runnable mPositionReporter = new Runnable() {
        @Override
        public void run() {
            if (mVideoView != null && !DlnaRenderState.currentUri.isEmpty()) {
                try {
                    int state = mVideoView.getCurrentPlayState();
                    long pos = mVideoView.getCurrentPosition();
                    long dur = mVideoView.getDuration();
                    if (pos > 0) DlnaRenderState.positionMs = pos;
                    if (dur > 0) DlnaRenderState.durationMs = dur;
                    if (state == xyz.doikki.videoplayer.player.VideoView.STATE_PLAYING) {
                        DlnaRenderState.TRANSPORT_STATE.set("PLAYING");
                        DlnaRenderState.TRANSPORT_STATUS.set("OK");
                    } else if (state == xyz.doikki.videoplayer.player.VideoView.STATE_PAUSED) {
                        DlnaRenderState.TRANSPORT_STATE.set("PAUSED_PLAYBACK");
                    } else if (state == xyz.doikki.videoplayer.player.VideoView.STATE_ERROR) {
                        DlnaRenderState.TRANSPORT_STATE.set("STOPPED");
                        DlnaRenderState.TRANSPORT_STATUS.set("ERROR_OCCURRED");
                        // 硬解失败回退 ijk 一次（https 推流等系统播放器拉不动的场景）
                        if (!retriedWithIjk && !currentUrl.isEmpty()) {
                            retriedWithIjk = true;
                            DlnaRenderState.TRANSPORT_STATUS.set("OK");
                            showToast("硬解失败，切换ijk重试");
                            PlayerHelper.updateCfg(mVideoView, new org.json.JSONObject(), 1);
                            mVideoView.setUrl(currentUrl);
                            mVideoView.start();
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            mHandler.postDelayed(this, 1000);
        }
    };

    private void showToast(String s) {
        try {
            Toast.makeText(mContext, s, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_cast;
    }

    @Override
    protected void init() {
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mVideoView = findViewById(R.id.castVideoView);
        tvTitle = findViewById(R.id.castTitle);
        // 跟随全局默认播放器（系统硬解优先）
        PlayerHelper.updateCfg(mVideoView, new org.json.JSONObject());
        mHandler.postDelayed(mPositionReporter, 1000);
    }

    private void playUri(String uri) {
        currentUrl = uri;
        retriedWithIjk = false;
        String name = uri;
        int slash = uri.lastIndexOf('/');
        if (slash >= 0 && slash < uri.length() - 1) name = uri.substring(slash + 1);
        int q = name.indexOf('?');
        if (q > 0) name = name.substring(0, q);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        tvTitle.setText("DLNA 投放：" + name);
        DlnaRenderState.TRANSPORT_STATE.set("TRANSITIONING");
        mVideoView.setUrl(uri);
        mVideoView.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        EventBus.getDefault().register(this);
        // 首次进入：SetAVTransportURI 已把 URI 写进 DlnaRenderState
        if (currentUrl.isEmpty() && !DlnaRenderState.currentUri.isEmpty()) {
            playUri(DlnaRenderState.currentUri);
        }
    }

    @Override
    protected void onPause() {
        EventBus.getDefault().unregister(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacks(mPositionReporter);
        if (mVideoView != null) {
            mVideoView.release();
            mVideoView = null;
        }
        DlnaRenderState.TRANSPORT_STATE.set("STOPPED");
        super.onDestroy();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastMsg(DlnaRendererService.CastMsg msg) {
        if (mVideoView == null) return;
        switch (msg.msg) {
            case DlnaRendererService.MSG_SET_URI:
                playUri(DlnaRenderState.currentUri);
                break;
            case DlnaRendererService.MSG_PLAY:
                if (currentUrl.isEmpty()) playUri(DlnaRenderState.currentUri);
                else mVideoView.start();
                break;
            case DlnaRendererService.MSG_PAUSE:
                mVideoView.pause();
                break;
            case DlnaRendererService.MSG_STOP:
                mVideoView.pause(); // 该分支 VideoView 无 stopPlay，pause+finish（onDestroy release）
                DlnaRenderState.TRANSPORT_STATE.set("STOPPED");
                finish();
                break;
            case DlnaRendererService.MSG_SEEK:
                try {
                    String[] parts = msg.seekTarget.split(":");
                    long ms = 0;
                    for (String p : parts) ms = ms * 60 + Long.parseLong(p.trim());
                    ms *= 1000;
                    mVideoView.seekTo(ms);
                } catch (Throwable ignored) {
                }
                break;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 遥控器返回 = 结束投屏会话
            DlnaRenderState.TRANSPORT_STATE.set("STOPPED");
            mVideoView.pause(); // 该分支 VideoView 无 stopPlay，pause+finish（onDestroy release）
            finish();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            int st = mVideoView.getCurrentPlayState();
            if (st == xyz.doikki.videoplayer.player.VideoView.STATE_PLAYING) mVideoView.pause();
            else mVideoView.start();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
