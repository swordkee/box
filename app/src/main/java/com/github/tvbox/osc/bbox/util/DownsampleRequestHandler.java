package com.github.tvbox.osc.bbox.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.squareup.picasso.Picasso;
import com.squareup.picasso.RequestHandler;

import java.io.IOException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * NOTE(2026-10-24 API15 port): 789MB 内存设备上，部分源站海报是 4000px 级巨图，
 * Picasso 全尺寸解码直接 OOM。此 handler 置顶注册接管所有 http(s) 图片：
 * 解码前读尺寸，按 maxDim 计算 inSampleSize 采样，RGB_565，巨图内存从 ~40MB 降到 ~1.5MB。
 */
public class DownsampleRequestHandler extends RequestHandler {
    private final OkHttpClient client;
    private final int maxDim;

    public DownsampleRequestHandler(OkHttpClient client, int maxDim) {
        this.client = client;
        this.maxDim = maxDim;
    }

    @Override
    public boolean canHandleRequest(com.squareup.picasso.Request data) {
        String scheme = data.uri.getScheme();
        return "http".equals(scheme) || "https".equals(scheme);
    }

    @Override
    public Result load(com.squareup.picasso.Request request, int networkPolicy) throws IOException {
        okhttp3.Request req = new okhttp3.Request.Builder()
                .url(request.uri.toString())
                .header("User-Agent", "okhttp/3.12.1")
                .build();
        Response resp = client.newCall(req).execute();
        byte[] bytes;
        try {
            bytes = resp.body().bytes();
        } finally {
            resp.close();
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= maxDim && bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        if (bmp == null) {
            opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        }
        if (bmp == null) throw new IOException("decode failed: " + request.uri);
        return new Result(bmp, Picasso.LoadedFrom.NETWORK);
    }
}
