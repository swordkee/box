package com.github.tvbox.osc.bbox.util;
import com.github.catvod.crawler.SpiderDebug;
import com.github.tvbox.osc.bbox.server.ControlManager;
import com.github.tvbox.osc.bbox.util.parser.SuperParse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import com.github.tvbox.osc.bbox.util.OkGoHelper;

public class Proxy {

    public static Object[] proxy(Map<String, String> params) {
        try {
            String what = params.get("go");
            assert what != null;
            if (what.equals("live")) {
                return itv(params);
            }
            else if (what.equals("bom")) {
                return removeBOMFromM3U8(params);
            }
            else if (what.equals("ad")) {
                //TODO
                return null;
            }
            else if (what.equals("SuperParse")) {
                return SuperParse.loadHtml(params.get("flag"), params.get("url"));
            }

        } catch (Throwable ignored) {

        }
        return null;
    }
    public static Object[] itv(Map<String, String> params) throws Exception {
        try {
            Object[] result = new Object[3];
            String url = params.get("url");
            String type = params.get("type");
            url = URLDecoder.decode(url,"UTF-8");

            OkHttpClient client = OkGoHelper.ItvClient;
            assert type != null;
            if (type.equals("m3u8")) {
                String redirectUrl = getRedirectedUrl(url);
//                LOG.i("echo-url"+redirectUrl);

                com.github.tvbox.osc.bbox.util.LOG.i("echo-itv-m3u8 " + redirectUrl);
                Request request = new Request.Builder().url(redirectUrl)
                        .header("User-Agent", "okhttp/3.12.0").build();
                try (Response response = executeRequest(client, request)) {
                    if (response.isSuccessful()) {
                        assert response.body() != null;
                        String respContent = response.body().string();
                        // NOTE(2026-10-24 API15 port): 分段/密钥/子清单全部改写走本地代理，
                        // 否则系统播放器(TLS1.0) 直连 https 分段必死
                        String m3u8Content = proxifyM3u8Lines(redirectUrl, processM3u8Content(respContent, redirectUrl));
                        result[0] = 200;
                        result[1] = "application/vnd.apple.mpegurl";
                        result[2] = new ByteArrayInputStream(m3u8Content.getBytes());
                    } else {
                        throw new IOException("M3U8 Request failed with code: " + response.code());
                    }
                }
            } else if (type.equals("ts")) {
                com.github.tvbox.osc.bbox.util.LOG.i("echo-itv-ts " + url);
                Request request = new Request.Builder().url(url)
                        .header("User-Agent", "okhttp/3.12.0").build();
                try (Response response = executeRequest(client, request)) {
                    com.github.tvbox.osc.bbox.util.LOG.i("echo-itv-ts code=" + response.code());
                    if (response.isSuccessful()) {
                        result[0] = 200;
                        result[1] = "video/mp2t";
                        assert response.body() != null;
                        result[2] = new ByteArrayInputStream(response.body().bytes());
                    } else {
                        throw new IOException("TS Request failed with code: " + response.code());
                    }
                }
            } else {
                throw new IllegalArgumentException("Invalid type: " + type);
            }
            return result;
        } catch (Exception e) {
            SpiderDebug.log(e);
            return null;
        }
    }

    public static Object[] removeBOMFromM3U8(Map<String, String> params) throws Exception {
        try {
            Object[] result = new Object[3];
            String url = params.get("url");
            url = URLDecoder.decode(url,"UTF-8");

            OkHttpClient client = OkGoHelper.ItvClient;
            String redirectUrl = getRedirectedUrl(url);
//                LOG.i("echo-url"+redirectUrl);

            Request request = new Request.Builder().url(redirectUrl).build();
            try (Response response = executeRequest(client, request)) {
                if (response.isSuccessful()) {
                    assert response.body() != null;
                    String m3u8Content = response.body().string();
                    // 检查并去除 UTF-8 BOM 头（BOM 为 \uFEFF）
                    if (m3u8Content.startsWith("\ufeff")) {
                        m3u8Content = m3u8Content.substring(1);
                    }
                    result[0] = 200;
                    result[1] = "application/vnd.apple.mpegurl";
                    result[2] = new ByteArrayInputStream(m3u8Content.getBytes());
                } else {
                    throw new IOException("M3U8 Request failed with code: " + response.code());
                }
            }
            return result;
        } catch (Exception e) {
            SpiderDebug.log(e);
            return null;
        }
    }

    private static Response executeRequest(OkHttpClient client, Request request) throws IOException {
        try {
            return client.newCall(request).execute();
        } catch (IOException e) {
            System.err.println("网络请求异常：" + e.getMessage());
            throw e; // 重新抛出异常，让外层处理
        }
    }

    private static String processM3u8Content(String m3u8Content, String m3u8Url) {
        String[] m3u8Lines = m3u8Content.trim().split("\n");
        StringBuilder processedM3u8 = new StringBuilder();

        for (String line : m3u8Lines) {
            if (line.startsWith("#")) {
                processedM3u8.append(line).append("\n");
            } else {
                processedM3u8.append(joinUrl(m3u8Url, line)).append("\n");
            }
        }
        return processedM3u8.toString().replace("\\n\\n", "\n");
    }

    // NOTE(2026-10-24 API15 port): 把 m3u8 内的分段/密钥/子清单全部改写为本地代理 URL
    // 系统播放器(硬解)只能搞 http，https 上游由本代理用 BC TLS1.2 取回
    public static String proxifyM3u8Lines(String basePath, String content) {
        String lineSep = content.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = content.split(lineSep);
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher m;
        for (String line : lines) {
            String t = line.trim();
            if (t.length() == 0) {
                sb.append(line).append(lineSep);
                continue;
            }
            if (t.startsWith("#EXT-X-KEY") || t.startsWith("#EXT-X-MAP")) {
                StringBuffer out = new StringBuffer();
                m = java.util.regex.Pattern.compile("URI=\"([^\"]+)\"").matcher(t);
                while (m.find()) {
                    String abs = joinUrl(basePath, m.group(1));
                    m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement("URI=\"" + localProxyUrl(abs, "ts") + "\""));
                }
                m.appendTail(out);
                sb.append(out).append(lineSep);
            } else if (!t.startsWith("#")) {
                String abs = joinUrl(basePath, t);
                String type = abs.contains(".m3u8") ? "m3u8" : "ts";
                sb.append(localProxyUrl(abs, type)).append(lineSep);
            } else {
                sb.append(line).append(lineSep);
            }
        }
        return sb.toString();
    }

    public static String localProxyUrl(String absUrl, String type) {
        // NOTE(2026-10-25): 幂等保护——播放器重试会让 processM3u8Content 二次进入，
        // 已本地化的 URL 必须原样返回，否则套娃嵌套 proxy URL → 播放出错
        if (absUrl == null || absUrl.contains(":9978/proxy?")) return absUrl;
        try {
            String addr = ControlManager.get().getAddress(true);
            // NOTE(2026-10-25): 不带 do=proxy —— do 参数会被 RemoteServer 优先路由到 jar 专用代理
                // (ApiConfig.proxyLocal)，无 jar 时 null → NPE → 分段 500。go=live 即路由 Proxy.proxy
                return addr + "proxy?go=live&type=" + type + "&url=" + URLEncoder.encode(absUrl, "UTF-8");
        } catch (Throwable th) {
            return absUrl;
        }
    }

    private static String joinUrl(String base, String url) {
        if (base == null) base = "";
        if (url == null) url = "";
        try {
            URI baseUri = new URI(base.trim());
            url = url.trim();
            URI urlUri = new URI(url);
            String proxyUrl = ControlManager.get().getAddress(true) + "proxy?go=live&type=ts&url=";
            if (url.startsWith("http://") || url.startsWith("https://")) {
                return proxyUrl + URLEncoder.encode(urlUri.toString(),"UTF-8");
            } else if (url.startsWith("://")) {
                return proxyUrl + URLEncoder.encode(new URI(baseUri.getScheme() + url).toString(),"UTF-8");
            } else if (url.startsWith("//")) {
                return proxyUrl + URLEncoder.encode(new URI(baseUri.getScheme() + ":" + url).toString(),"UTF-8");
            } else {
                URI resolvedUri = baseUri.resolve(url);
                return proxyUrl + URLEncoder.encode(resolvedUri.toString(),"UTF-8");
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String getRedirectedUrl(String url) throws IOException {
        OkHttpClient client = OkGoHelper.newBcClient()
                .followRedirects(false) // 不自动跟随重定向
                .build();

        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isRedirect()) { // 判断是否为重定向
                return response.header("Location"); // 获取重定向后的地址
            }
            return url; // 如果没有重定向，返回原 URL
        }
    }

    public static String getM3U8Content(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .build();

        OkHttpClient client = OkGoHelper.ItvClient;
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                return response.body().string(); // 获取 m3u8 文件内容
            } else {
                throw new IOException("请求失败，HTTP 状态码: " + response.code());
            }
        }
    }

}
