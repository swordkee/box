package com.github.tvbox.osc.bbox.util;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.bbox.api.ApiConfig;
import com.github.tvbox.osc.bbox.base.App;
import com.github.tvbox.osc.bbox.util.BcTls;
import com.github.tvbox.osc.bbox.util.HawkConfig;
import com.github.tvbox.osc.bbox.util.SSL.SSLSocketFactoryCompat;
import com.github.tvbox.osc.bbox.picasso.MyOkhttpDownLoader;
import com.github.tvbox.osc.bbox.util.SSL.SSLSocketFactoryCompat;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.https.HttpsUtils;
import com.lzy.okgo.interceptor.HttpLoggingInterceptor;
import com.lzy.okgo.model.HttpHeaders;
import com.orhanobut.hawk.Hawk;
import com.squareup.picasso.Picasso;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.Cache;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.dnsoverhttps.DnsOverHttps;
import okhttp3.internal.Version;
import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper;


public class OkGoHelper {
    public static final long DEFAULT_MILLISECONDS = 10000;      //默认的超时时间

    // 内置doh json
    private static final String dnsConfigJson = "["
            + "{\"name\": \"腾讯\", \"url\": \"https://doh.pub/dns-query\"},"
            + "{\"name\": \"阿里\", \"url\": \"https://dns.alidns.com/dns-query\"},"
            + "{\"name\": \"360\", \"url\": \"https://doh.360.cn/dns-query\"}"
            + "]";
    static OkHttpClient ItvClient = null;
    static void initExoOkHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor("OkExoPlayer");

        if (Hawk.get(HawkConfig.DEBUG_OPEN, false)) {
            loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.BODY);
            loggingInterceptor.setColorLevel(Level.INFO);
        } else {
            loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.NONE);
            loggingInterceptor.setColorLevel(Level.OFF);
        }
        builder.addInterceptor(loggingInterceptor);

        builder.retryOnConnectionFailure(true);
        builder.followRedirects(true);
        builder.followSslRedirects(true);


        try {
            setOkHttpSsl(builder);
        } catch (Throwable th) {
            th.printStackTrace();
        }

//        builder.dns(dnsOverHttps);
        builder.dns(new CustomDns(dnsOverHttps));
        ItvClient=builder.build();

        ExoMediaSourceHelper.getInstance(App.getInstance()).setOkClient(ItvClient);
    }

    public static DnsOverHttps dnsOverHttps = null;

    public static ArrayList<String> dnsHttpsList = new ArrayList<>();

    public static boolean is_doh = false;
    public static Map<String, String> myHosts = null;

    public static String getDohUrl(int type) {
        String json=Hawk.get(HawkConfig.DOH_JSON,"");
        if(json.isEmpty())json=dnsConfigJson;
        JsonArray jsonArray = JsonParser.parseString(json).getAsJsonArray();
        if (type >= 1 && type < dnsHttpsList.size()) {
            JsonObject dnsConfig = jsonArray.get(type - 1).getAsJsonObject();
            // NOTE(2026-10-26 API15 port): 裸 get —— dns 配置项缺 url 时抛异常，
            // 会让整个 DoH 初始化失败并回退成系统 DNS（电视上系统 DNS 常常解析不了）。
            return DefaultConfig.safeJsonString(dnsConfig, "url", "");
        }
        return "";
    }

    public static void setDnsList() {
        dnsHttpsList.clear();
        String json=Hawk.get(HawkConfig.DOH_JSON,"");
        if(json.isEmpty())json=dnsConfigJson;
        JsonArray jsonArray = JsonParser.parseString(json).getAsJsonArray();
        dnsHttpsList.add("关闭");
        for (int i = 0; i < jsonArray.size(); i++) {
            JsonObject dnsConfig = jsonArray.get(i).getAsJsonObject();
            String name = DefaultConfig.safeJsonString(dnsConfig, "name", "Unknown Name");
            dnsHttpsList.add(name);
        }
        if(Hawk.get(HawkConfig.DOH_URL, 0)+1>dnsHttpsList.size())Hawk.put(HawkConfig.DOH_URL, 0);

    }

    private static List<InetAddress> DohIps(JsonArray ips) {
        List<InetAddress> inetAddresses = new ArrayList<>();
        if (ips != null) {
            for (int j = 0; j < ips.size(); j++) {
                try {
                    InetAddress inetAddress = InetAddress.getByName(ips.get(j).getAsString());
                    inetAddresses.add(inetAddress);  // 添加到 List 中
                } catch (Exception e) {
                    e.printStackTrace();  // 处理无效的 IP 字符串
                }
            }
        }
        return inetAddresses;
    }

    static void initDnsOverHttps() {
        Integer dohSelector=Hawk.get(HawkConfig.DOH_URL, 0);
        JsonArray ips=null;
        try {
            dnsHttpsList.add("关闭");
            String json=Hawk.get(HawkConfig.DOH_JSON,"");
            if(json.isEmpty())json=dnsConfigJson;
            JsonArray jsonArray = JsonParser.parseString(json).getAsJsonArray();
            if(dohSelector+1>jsonArray.size())Hawk.put(HawkConfig.DOH_URL, 0);
            for (int i = 0; i < jsonArray.size(); i++) {
                JsonObject dnsConfig = jsonArray.get(i).getAsJsonObject();
                String name = DefaultConfig.safeJsonString(dnsConfig, "name", "Unknown Name");
                dnsHttpsList.add(name);
                if(dohSelector==(i+1))ips = dnsConfig.has("ips") ? dnsConfig.getAsJsonArray("ips") : null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor("OkExoPlayer");
        if (Hawk.get(HawkConfig.DEBUG_OPEN, false)) {
            loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.BODY);
            loggingInterceptor.setColorLevel(Level.INFO);
        } else {
            loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.NONE);
            loggingInterceptor.setColorLevel(Level.OFF);
        }
        builder.addInterceptor(loggingInterceptor);
        try {
            setOkHttpSsl(builder);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        builder.cache(new Cache(new File(App.getInstance().getCacheDir().getAbsolutePath(), "dohcache"), 100 * 1024 * 1024));
        OkHttpClient dohClient = builder.build();
        String dohUrl = getDohUrl(Hawk.get(HawkConfig.DOH_URL, 0));
//        if (!dohUrl.isEmpty()) is_doh = true;
//        LOG.i("echo-initDnsOverHttps dohUrl:"+dohUrl);
//        LOG.i("echo-initDnsOverHttps ips:"+ips);
        dnsOverHttps = new DnsOverHttps.Builder().client(dohClient).url(dohUrl.isEmpty() ? null : HttpUrl.get(dohUrl)).bootstrapDnsHosts((ips!=null && !dohUrl.equals("https://doh.pub/dns-query"))?DohIps(ips):null).build();
    }

    // 自定义 DNS 解析器
    static class CustomDns implements Dns {
        private  ConcurrentHashMap<String, List<InetAddress>> map;
        private final String excludeIps = "2409:8087:6c02:14:100::14,2409:8087:6c02:14:100::18,39.134.108.253,39.134.108.245";
        private final DnsOverHttps mDnsOverHttps;

        // 接收外部注入的 DoH 实例
        public CustomDns(DnsOverHttps dnsOverHttps) {
            this.mDnsOverHttps = dnsOverHttps;
        }
        @NonNull
        @Override
        public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
            if (myHosts == null){
                myHosts = ApiConfig.get().getMyHost(); //确保只获取一次减少消耗
            }
            else if(!myHosts.isEmpty() && myHosts.containsKey(hostname)) {
                hostname=myHosts.get(hostname);
            }
            assert hostname != null;
            if (isValidIpAddress(hostname)) {
                return Collections.singletonList(InetAddress.getByName(hostname));
            }
            else {
                return  mDnsOverHttps.lookup(hostname);
            }
        }

        public synchronized void mapHosts(Map<String,String> hosts) throws UnknownHostException {
            map=new ConcurrentHashMap<>();
            for (Map.Entry<String, String> entry : hosts.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if(isValidIpAddress(value)){
                    map.put(key,Collections.singletonList(InetAddress.getByName(value)));
                }else {
                    map.put(key,getAllByName(value));
                }
            }
        }

        private List<InetAddress> getAllByName(String host) {
            try {
                // 获取所有与主机名关联的 IP 地址
                InetAddress[] allAddresses = InetAddress.getAllByName(host);
                if(excludeIps.isEmpty())return Arrays.asList(allAddresses);
                // 创建一个列表用于存储有效的 IP 地址
                List<InetAddress> validAddresses = new ArrayList<>();
                Set<String> excludeIpsSet = new HashSet<>();
                for (String ip : excludeIps.split(",")) {
                    excludeIpsSet.add(ip.trim());  // 添加到集合，去除多余的空格
                }
                for (InetAddress address : allAddresses) {
                    if (!excludeIpsSet.contains(address.getHostAddress())) {
                        validAddresses.add(address);
                    }
                }
                return validAddresses;
            } catch (Exception e) {
                return new ArrayList<>();
            }
        }

        //简单判断减少开销
        private boolean isValidIpAddress(String str) {
            if (str.indexOf('.') > 0) return isValidIPv4(str);
            return str.indexOf(':') > 0;
        }

        private boolean isValidIPv4(String str) {
            String[] parts = str.split("\\.");
            if (parts.length != 4) return false;
            for (String part : parts) {
                try {
                    Integer.parseInt(part);
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            return true;
        }
    }

    static OkHttpClient defaultClient = null;
    static OkHttpClient noRedirectClient = null;

    public static OkHttpClient getDefaultClient() {
        return defaultClient;
    }

    public static OkHttpClient getNoRedirectClient() {
        return noRedirectClient;
    }

    public static void init() {
        initDnsOverHttps();

        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor("OkGo");

        if (Hawk.get(HawkConfig.DEBUG_OPEN, false)) {
            loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.BODY);
            loggingInterceptor.setColorLevel(Level.INFO);
        } else {
            loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.NONE);
            loggingInterceptor.setColorLevel(Level.OFF);
        }

        //builder.retryOnConnectionFailure(false);

        builder.addInterceptor(loggingInterceptor);

        builder.readTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS);
        builder.writeTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS);
        builder.connectTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS);

        builder.dns(dnsOverHttps);
        try {
            setOkHttpSsl(builder);
        } catch (Throwable th) {
            th.printStackTrace();
        }

        HttpHeaders.setUserAgent(Version.userAgent());

        OkHttpClient okHttpClient = builder.build();
        OkGo.getInstance().setOkHttpClient(okHttpClient);

        defaultClient = okHttpClient;

        builder.followRedirects(false);
        builder.followSslRedirects(false);
        noRedirectClient = builder.build();

        initExoOkHttpClient();
        initPicasso(okHttpClient);
    }

    static void initPicasso(OkHttpClient client) {
        client.dispatcher().setMaxRequestsPerHost(10);
        MyOkhttpDownLoader downloader = new MyOkhttpDownLoader(client);
        // NOTE(2026-10-25): 1GB 机器内存优化——Picasso 默认内存缓存约占可用堆 1/7（~100MB），
        // 与本机硬解/ijk 抢内存易 OOM；显式限到 24MB（约 1000 张 160x120 RGB565 缩略图）
        final int memCacheSize = 24 * 1024 * 1024;
        com.squareup.picasso.LruCache memCache = new com.squareup.picasso.LruCache(memCacheSize);
        Picasso picasso = new Picasso.Builder(App.getInstance())
                .downloader(downloader)
                .memoryCache(memCache)
                .defaultBitmapConfig(Bitmap.Config.RGB_565)
                .addRequestHandler(new DownsampleRequestHandler(client, 640))
                .build();
        Picasso.setSingletonInstance(picasso);
    }

    /** 预注入 BC TLS 栈的客户端构建器（API15 系统 JSSE 无 TLS1.2） */
    public static OkHttpClient.Builder newBcClient() {
        OkHttpClient.Builder b = new OkHttpClient.Builder();
        setOkHttpSsl(b);
        return b;
    }

    public static synchronized void setOkHttpSsl(OkHttpClient.Builder builder) {
        try {
            // 自定义一个信任所有证书的TrustManager，添加SSLSocketFactory的时候要用到
            final X509TrustManager trustAllCert =
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                        }

                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                        }

                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[]{};
                        }
                    };
            // NOTE(2026-10-24 API15 port): 系统JSSE只有TLS1.0，换 BouncyCastle 轻量 TLS 栈支持 TLS1.2/1.3
            if (BcTls.available()) {
                builder.sslSocketFactory(BcTls.factory(), trustAllCert);
            } else {
                builder.sslSocketFactory(new SSLSocketFactoryCompat(trustAllCert), trustAllCert);
            }
            builder.hostnameVerifier(HttpsUtils.UnSafeHostnameVerifier);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
