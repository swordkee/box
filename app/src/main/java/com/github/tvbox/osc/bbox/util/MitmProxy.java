package com.github.tvbox.osc.bbox.util;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.net.ssl.SSLContext;

/**
 * NOTE(2026-10-25 API15 port): \u8fdb\u7a0b\u5185 MITM \u4ee3\u7406 \u2014\u2014 jar \u805a\u5408\u6e90 https \u89e3\u5c01\u7684\u4f20\u8f93\u5c42
 * <p>
 * \u67b6\u6784\uff1aProxySelector.setDefault \u628a\u672c\u8fdb\u7a0b\u7684 https \u8fde\u63a5\u5f15\u5230 127.0.0.1:8888\uff08CONNECT\uff09\uff0c
 * \u672c\u4ee3\u7406\u7528 TlsServerProtocol\uff08\u7eaf BC \u8f7b\u91cf\uff0c\u63a5\u53d7 TLS1.0 \u5ba2\u6237\u7aef\uff09\u505a\u670d\u52a1\u7aef TLS\uff0c
 * \u5bf9\u4e0a\u6e38\u7528 BcTls.socket\uff08BC TLS1.2\uff09\u62c9\u6d41\uff0c\u4e2d\u95f4\u660e\u6587\u5bf9\u62f7\u3002
 * <p>
 * \u8303\u56f4\uff1a\u4ec5\u672c\u8fdb\u7a0b\uff08ProxySelector \u8fdb\u7a0b\u5c5e\u6027\uff09\uff0c\u94f6\u6cb3/\u817e\u8baf\u7b49\u4e0d\u53d7\u5f71\u54cd\uff1b
 * loopback\uff08127.0.0.1:9978 \u914d\u7f6e/\u53cd\u4ee3\uff09\u76f4\u8fde\u4e0d\u7ecf MITM\u3002
 */
public class MitmProxy {

    private static final int PORT = 8888;
    private static volatile ServerSocket serverSocket;
    private static volatile org.bouncycastle.jsse.provider.BouncyCastleJsseProvider jsseProvider;
    private static final List<Socket> openSockets = new CopyOnWriteArrayList();

    public static boolean start() {
        if (!BcTls.available()) return false;
        if (serverSocket != null) return true;
        synchronized (MitmProxy.class) {
            if (serverSocket != null) return true;
            try {
                ServerSocket ss = new ServerSocket(PORT, 128, InetAddress.getByName("127.0.0.1"));
                serverSocket = ss;
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() { acceptLoop(ss); }
                }, "MitmProxy");
                t.setDaemon(true);
                t.start();
                LOG.i("MitmProxy started on 127.0.0.1:" + PORT);
                return true;
            } catch (Throwable th) {
                LOG.e("MitmProxy start failed: " + th);
                serverSocket = null;
                return false;
            }
        }
    }

    public static boolean running() { return serverSocket != null; }

    /** BCJSSE provider\uff08\u670d\u52a1\u7aef TLS \u7528\uff0c\u53ea\u521b\u4e00\u6b21\uff09 */
    public static synchronized org.bouncycastle.jsse.provider.BouncyCastleJsseProvider jsseProvider() {
        if (jsseProvider == null) {
            org.bouncycastle.jce.provider.BouncyCastleProvider bc =
                    new org.bouncycastle.jce.provider.BouncyCastleProvider();
            jsseProvider = new org.bouncycastle.jsse.provider.BouncyCastleJsseProvider(bc);
        }
        return jsseProvider;
    }

    private static void acceptLoop(ServerSocket ss) {
        while (true) {
            final Socket client;
            try { client = ss.accept(); } catch (Throwable th) { return; }
            openSockets.add(client);
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try { handle(client); } catch (Throwable th) { LOG.e("MitmProxy conn: " + th); }
                    finally { openSockets.remove(client); try { client.close(); } catch (Throwable ignored) {} }
                }
            }, "MitmConn");
            t.setDaemon(true);
            t.start();
        }
    }

    private static void handle(final Socket client) throws Exception {
        client.setSoTimeout(30000);
        InputStream in = client.getInputStream();
        String firstLine = readLine(in);
        if (firstLine == null || firstLine.length() == 0) return;
        if (firstLine.startsWith("CONNECT")) {
            String target = firstLine.split(" ")[1];
            String host = target.split(":")[0];
            int port = target.contains(":") ? Integer.parseInt(target.split(":")[1]) : 443;
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) { }
            if (host.equals("127.0.0.1") || host.equals("localhost")) {
                Socket local = new Socket("127.0.0.1", port);
                client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes("UTF-8"));
                client.getOutputStream().flush();
                pipeBidirectional(client, local);
                return;
            }
            client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes("UTF-8"));
            client.getOutputStream().flush();
            client.setSoTimeout(0);
            mitmTunnel(client, host, port);
        } else if (firstLine.startsWith("GET") || firstLine.startsWith("POST")) {
            String[] parts = firstLine.split(" ");
            java.net.URL u = new java.net.URL(parts[1]);
            String host = u.getHost();
            int port = u.getPort() > 0 ? u.getPort() : 80;
            Socket upstream = new Socket(host, port);
            upstream.getOutputStream().write(("GET " + u.getFile() + " HTTP/1.1\r\nHost: " + host +
                    "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            upstream.getOutputStream().flush();
            pipeBidirectional(client, upstream);
            try { upstream.close(); } catch (Throwable ignored) {}
        }
    }

    /** MITM \u6838\u5fc3\uff1a\u5bf9 jar \u5ba2\u6237\u7aef\u63d0\u4f9b TLS1.0 \u670d\u52a1\u7aef\uff08\u52a8\u6001\u8bc1\u4e66\uff09\uff0c\u5bf9\u4e0a\u6e38\u7528 BcTls TLS1.2 */
    private static void mitmTunnel(Socket client, String host, int port) throws Exception {
        MitmCertManager.Leaf leaf = MitmCertManager.leafFor(host);
        if (leaf == null) throw new java.io.IOException("no leaf cert for " + host);
        // TLS \u670d\u52a1\u7aef\uff1a\u7eaf BC \u8f7b\u91cf\u5b9e\u73b0\uff0c\u63a5\u53d7 jar \u5ba2\u6237\u7aef\u7684 TLS1.0
        org.bouncycastle.tls.TlsServerProtocol proto = new org.bouncycastle.tls.TlsServerProtocol(
                client.getInputStream(), client.getOutputStream());
        MitmTlsServer server = new MitmTlsServer(
                (org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto) BcTls.crypto(),
                leaf.cert, MitmCertManager.getCaCert(), leaf.priv);
        proto.accept(server);
        // \u63e1\u624b\u5b8c\u6210\u540e\uff0c\u4e2d\u95f4\u660e\u6587\u53cc\u5411\u5bf9\u62f7
        InputStream clientIn = proto.getInputStream();
        OutputStream clientOut = proto.getOutputStream();
        // BcTlsSocket 惰性握手：必须显式 startHandshake，否则 getOutputStream 返回裸 TCP 流，
        // 明文 HTTP 写进 TLS 端口会被服务器 RST（EPIPE）
        javax.net.ssl.SSLSocket upTls = (javax.net.ssl.SSLSocket) BcTls.factory().createSocket(host, port);
        upTls.setSoTimeout(30000);
        upTls.startHandshake();
        Socket upstream = upTls;
        InputStream upIn = upstream.getInputStream();
        OutputStream upOut = upstream.getOutputStream();
        Thread t1 = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[32768];
                try {
                    int n;
                    while ((n = clientIn.read(buf)) > 0) upOut.write(buf, 0, n);
                    upOut.flush();
                } catch (Throwable ignored) {}
                try { upstream.shutdownOutput(); } catch (Throwable ignored) {}
            }
        }, "mitm-c2u");
        Thread t2 = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[32768];
                try {
                    int n;
                    while ((n = upIn.read(buf)) > 0) clientOut.write(buf, 0, n);
                    clientOut.flush();
                } catch (Throwable ignored) {}
                try { client.shutdownOutput(); } catch (Throwable ignored) {}
            }
        }, "mitm-u2c");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        try { upstream.close(); } catch (Throwable ignored) {}
        try { proto.close(); } catch (Throwable ignored) {}
        try { client.close(); } catch (Throwable ignored) {}
    }

    private static void pipeBidirectional(Socket a, Socket b) throws Exception {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[32768];
                try {
                    InputStream in = a.getInputStream();
                    OutputStream out = b.getOutputStream();
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.flush();
                } catch (Throwable ignored) {}
                try { b.shutdownOutput(); } catch (Throwable ignored) {}
            }
        });
        t.setDaemon(true);
        t.start();
        byte[] buf = new byte[32768];
        try {
            InputStream in = b.getInputStream();
            OutputStream out = a.getOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } catch (Throwable ignored) {}
        try { a.shutdownOutput(); } catch (Throwable ignored) {}
        t.join(10000);
        try { a.close(); } catch (Throwable ignored) {}
        try { b.close(); } catch (Throwable ignored) {}
    }

    private static String readLine(InputStream in) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }
}
