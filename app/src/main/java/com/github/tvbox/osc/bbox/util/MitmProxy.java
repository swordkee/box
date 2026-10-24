package com.github.tvbox.osc.bbox.util;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * NOTE(2026-10-25 API15 port): \u8fdb\u7a0b\u5185 MITM \u4ee3\u7406 \u2014\u2014 jar \u805a\u5408\u6e90 https \u89e3\u5c01\u7684\u4f20\u8f93\u5c42
 * <p>
 * \u539f\u7406\uff1aProxySelector.setDefault \u628a\u672c\u8fdb\u7a0b\u7684 https \u8bf7\u6c42\u5f15\u5230 127.0.0.1:8888\uff08CONNECT\uff09\uff0c
 * \u672c\u4ee3\u7406\u5bf9 jar \u5ba2\u6237\u7aef\u7528\u52a8\u6001\u8bc1\u4e66\u63d0\u4f9b TLS1.0 \u670d\u52a1\u7aef\uff08\u8001\u5e73\u53f0\u65e0\u7248\u672c\u4e0b\u9650\uff09\uff0c
 * \u5bf9\u4e0a\u6e38\u7528 BcTls\uff08BC TLS1.2\uff09\u62c9\u6d41\uff0c\u4e2d\u95f4\u660e\u6587\u5bf9\u62f7\u3002
 * <p>
 * \u8303\u56f4\uff1a\u4ec5\u672c\u8fdb\u7a0b\uff08ProxySelector \u662f\u8fdb\u7a0b\u5c5e\u6027\uff09\uff0c\u94f6\u6cb3/\u817e\u8baf\u7b49\u5176\u4ed6\u5e94\u7528\u4e0d\u53d7\u5f71\u54cd\uff1b
 * loopback\uff08127.0.0.1:9978 \u914d\u7f6e/\u53cd\u4ee3\uff09\u76f4\u8fde\u4e0d\u7ecf MITM\u3002
 */
public class MitmProxy {

    private static final int PORT = 8888;
    private static volatile ServerSocket serverSocket;
    private static final List<Socket> openSockets = new CopyOnWriteArrayList<>();

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
                    public void run() {
                        acceptLoop(ss);
                    }
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

    public static boolean running() {
        return serverSocket != null;
    }

    private static void acceptLoop(ServerSocket ss) {
        while (true) {
            final Socket client;
            try {
                client = ss.accept();
            } catch (Throwable th) {
                return;
            }
            openSockets.add(client);
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        handle(client);
                    } catch (Throwable th) {
                        LOG.e("MitmProxy conn error: " + th);
                    } finally {
                        openSockets.remove(client);
                        try { client.close(); } catch (Throwable ignored) {}
                    }
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
            String target = firstLine.split(" ")[1]; // host:port
            String host = target.split(":")[0];
            int port = target.contains(":") ? Integer.parseInt(target.split(":")[1]) : 443;
            // \u8bfb\u5b8c\u8bf7\u6c42\u5934
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) { }
            if (host.equals("127.0.0.1") || host.equals("localhost")) {
                // \u672c\u673a\u670d\u52a1\uff089978 \u914d\u7f6e/\u53cd\u4ee3\uff09\u76f4\u8fde\uff0c\u4e0d\u7ecf MITM
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
            // \u7edd\u5bf9\u8def\u5f84\u7684\u666e\u901a http \u8bf7\u6c42\uff1a\u63d0\u53d6 URL \u8f6c\u53d1\uff08\u5c11\u7528\uff0c\u4e3b\u8981\u4e3a\u517c\u5bb9\uff09
            StringBuilder headers = new StringBuilder(firstLine).append("\r\n");
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                headers.append(line).append("\r\n");
            }
            String[] parts = firstLine.split(" ");
            String fullUrl = parts[1];
            java.net.URL u = new java.net.URL(fullUrl);
            String host = u.getHost();
            int port = u.getPort() > 0 ? u.getPort() : 80;
            // \u4f7f\u7528 BC \u6808\u62c9\u4e0a\u6e38\uff08http \u65e0 TLS \u95ee\u9898\uff0c\u76f4\u8fde\u4e5f\u53ef\uff09
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
        KeyManagerFactory kmf = MitmCertManager.keyManagerFor(host);
        if (kmf == null) throw new java.io.IOException("no key manager for " + host);
        SSLContext serverCtx = SSLContext.getInstance("TLS");
        serverCtx.init(kmf.getKeyManagers(), null, new SecureRandom());
        javax.net.ssl.SSLSocketFactory serverFactory = serverCtx.getSocketFactory();
        // \u5728\u5df2\u63a5\u53d7\u7684\u8fde\u63a5\u4e0a\u505a TLS \u670d\u52a1\u7aef\u63e1\u624b\uff08\u63a5\u53d7\u8001\u5ba2\u6237\u7684 TLS1.0\uff09
        javax.net.ssl.SSLSocket tlsServer = (javax.net.ssl.SSLSocket) serverFactory
                .createSocket(client, host, port, false);
        tlsServer.setUseClientMode(false);
        tlsServer.startHandshake();

        // \u4e0a\u6e38\uff1aBcTls\uff08BC TLS1.2 + \u63a5\u53d7\u4efb\u610f\u8bc1\u4e66\uff09
        Socket upstream = BcTls.socket(host, port);

        InputStream clientIn = tlsServer.getInputStream();
        OutputStream clientOut = tlsServer.getOutputStream();
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
                try { tlsServer.shutdownOutput(); } catch (Throwable ignored) {}
            }
        }, "mitm-u2c");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        try { upstream.close(); } catch (Throwable ignored) {}
        try { tlsServer.close(); } catch (Throwable ignored) {}
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
        int prev = -1, c;
        while ((c = in.read()) != -1) {
            if (prev == '\r' && c == '\n') break;
            if (prev != -1 && prev != '\r') sb.append((char) prev);
            prev = c;
        }
        return sb.length() > 0 || prev == '\r' ? sb.toString() : (c == -1 && sb.length() == 0 && prev == -1 ? null : sb.toString());
    }
}
