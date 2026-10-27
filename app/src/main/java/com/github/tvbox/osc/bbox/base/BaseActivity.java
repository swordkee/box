package com.github.tvbox.osc.bbox.base;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.PermissionChecker;
import com.github.tvbox.osc.bbox.R;
import com.github.tvbox.osc.bbox.callback.EmptyCallback;
import com.github.tvbox.osc.bbox.callback.LoadingCallback;
import com.github.tvbox.osc.bbox.util.AppManager;
import com.kingja.loadsir.callback.Callback;
import com.kingja.loadsir.core.LoadService;
import com.kingja.loadsir.core.LoadSir;
import me.jessyan.autosize.AutoSizeCompat;
import me.jessyan.autosize.internal.CustomAdapt;
import xyz.doikki.videoplayer.util.CutoutUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public abstract class BaseActivity extends AppCompatActivity implements CustomAdapt {
    protected Context mContext;
    private LoadService mLoadService;

    private static float screenRatio = -100.0f;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        try {
            if (screenRatio < 0) {
                DisplayMetrics dm = new DisplayMetrics();
                getWindowManager().getDefaultDisplay().getMetrics(dm);
                int screenWidth = dm.widthPixels;
                int screenHeight = dm.heightPixels;
                screenRatio = (float) Math.max(screenWidth, screenHeight) / (float) Math.min(screenWidth, screenHeight);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        super.onCreate(savedInstanceState);
        setContentView(getLayoutResID());
        mContext = this;
        CutoutUtil.adaptCutoutAboveAndroidP(mContext, true);//设置刘海
        AppManager.getInstance().addActivity(this);
        init();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSysBar();
        changeWallpaper(false);
    }

    public void hideSysBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            int uiOptions = getWindow().getDecorView().getSystemUiVisibility();
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            uiOptions |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            uiOptions |= View.SYSTEM_UI_FLAG_FULLSCREEN;
            uiOptions |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(uiOptions);
        }
    }

    @Override
    public Resources getResources() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AutoSizeCompat.autoConvertDensityOfCustomAdapt(super.getResources(), this);
        }
        return super.getResources();
    }

    public boolean hasPermission(String permission) {
        boolean has = true;
        try {
            has = PermissionChecker.checkSelfPermission(this, permission) == PermissionChecker.PERMISSION_GRANTED;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return has;
    }

    protected abstract int getLayoutResID();


    protected boolean isLoading() {
        if (mLoadService != null && mLoadService.getCurrentCallback() != null) {
            return mLoadService.getCurrentCallback().equals(LoadingCallback.class);
        }
        return false;
    }

    protected abstract void init();

    protected void setLoadSir(View view) {
        if (mLoadService == null) {
            mLoadService = LoadSir.getDefault().register(view, new Callback.OnReloadListener() {
                @Override
                public void onReload(View v) {
                }
            });
        }
    }

    protected void showLoading() {
        if (mLoadService != null) {
            mLoadService.showCallback(LoadingCallback.class);
        }
    }

    protected void showEmpty() {
        if (null != mLoadService) {
            mLoadService.showCallback(EmptyCallback.class);
        }
    }

    protected void showSuccess() {
        if (null != mLoadService) {
            mLoadService.showSuccess();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppManager.getInstance().finishActivity(this);
    }

    public void jumpActivity(Class<? extends BaseActivity> clazz) {
        Intent intent = new Intent(mContext, clazz);
        startActivity(intent);
    }

    public void jumpActivity(Class<? extends BaseActivity> clazz, Bundle bundle) {
        Intent intent = new Intent(mContext, clazz);
        intent.putExtras(bundle);
        startActivity(intent);
    }

    protected String getAssetText(String fileName) {
        StringBuilder stringBuilder = new StringBuilder();
        try {
            AssetManager assets = getAssets();
            BufferedReader bf = new BufferedReader(new InputStreamReader(assets.open(fileName)));
            String line;
            while ((line = bf.readLine()) != null) {
                stringBuilder.append(line);
            }
            return stringBuilder.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }

    @Override
    public float getSizeInDp() {
        return isBaseOnWidth() ? 1280 : 720;
    }

    @Override
    public boolean isBaseOnWidth() {
        return !(screenRatio >= 4.0f);
    }

    protected static BitmapDrawable globalWp = null;
    /** 兜底背景只解码一次（原来每次 OOM 后都重新 setBackgroundDrawableResource，二次 OOM 直接崩） */
    private static Drawable fallbackBg = null;
    /** 一旦解码失败就置位，本进程内不再尝试任何 Bitmap 壁纸（1GB 机器宁可用纯色也别崩） */
    private static volatile boolean wallpaperGiveUp = false;

    public void changeWallpaper(boolean force) {
        // NOTE(2026-10-25): 缓存命中必须直接返回——原逻辑 set 完 drawable 后仍继续 decode，
        // 每次 onResume 都全量解一张壁纸大图 → 1GB 机器必 OOM（点详情即崩的主因）
        if (!force && globalWp != null) {
            try {
                getWindow().setBackgroundDrawable(globalWp);
                return;
            } catch (Throwable ignore) {
                // set 失败也不要紧，继续走兜底
            }
        }
        // NOTE(2026-10-27): 已经确认过这台机器解不动位图，就别再试了——
        // 之前是「每次 onResume 都重试 → 每次都 OOM → 点进详情必崩」。
        if (wallpaperGiveUp) {
            applyFallbackBg();
            return;
        }
        try {
            File wp = new File(getFilesDir().getAbsolutePath() + "/wp");
            if (wp.exists()) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(wp.getAbsolutePath(), opts);
                // 从Options中获取图片的分辨率
                int imageHeight = opts.outHeight;
                int imageWidth = opts.outWidth;
                int picHeight = 720;
                int picWidth = 1080;
                int scaleX = imageWidth / picWidth;
                int scaleY = imageHeight / picHeight;
                int scale = 1;
                if (scaleX > scaleY && scaleY >= 1) {
                    scale = scaleX;
                }
                if (scaleX < scaleY && scaleX >= 1) {
                    scale = scaleY;
                }
                opts.inJustDecodeBounds = false;
                // 采样率（至少 1，按屏幕尺寸自适应）
                if (scale < 1) scale = 1;
                int screenW = getResources().getDisplayMetrics().widthPixels;
                int screenH = getResources().getDisplayMetrics().heightPixels;
                while (imageWidth / (scale * 2) >= screenW && imageHeight / (scale * 2) >= screenH) {
                    scale *= 2;
                }
                opts.inSampleSize = scale;
                opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565; // 省一半内存
                globalWp = new BitmapDrawable(getResources(),
                        BitmapFactory.decodeFile(wp.getAbsolutePath(), opts));
            } else {
                globalWp = null;
            }
        } catch (OutOfMemoryError oom) {
            // 明显是解不动了：本进程内彻底放弃位图壁纸
            oom.printStackTrace();
            globalWp = null;
            wallpaperGiveUp = true;
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            globalWp = null;
        }
        if (globalWp != null) {
            try {
                getWindow().setBackgroundDrawable(globalWp);
            } catch (Throwable th) {
                applyFallbackBg();
            }
        } else {
            applyFallbackBg();
        }
    }

    /**
     * 应用兜底背景。
     * ★ 关键：这里绝不能再直接 setBackgroundDrawableResource —— 那会在 OOM 之后
     * 再解一张位图（R8 mapping 证实崩溃点正是原来的第 237 行），二次 OOM 没有保护。
     * 兜底图只解码一次并缓存；连它都失败就退到纯色。
     */
    private void applyFallbackBg() {
        if (fallbackBg == null) {
            try {
                fallbackBg = getResources().getDrawable(R.drawable.app_bg);
            } catch (Throwable th) {
                fallbackBg = null;
            }
        }
        try {
            if (fallbackBg != null) {
                getWindow().setBackgroundDrawable(fallbackBg);
                return;
            }
        } catch (Throwable ignore) {
        }
        try {
            // NOTE: Window 没有 setBackgroundColor，纯色兜底只能设在 decorView 上
            getWindow().getDecorView().setBackgroundColor(0xFF101418);
        } catch (Throwable ignore) {
        }
    }
}