package com.github.tvbox.osc.bbox.util;

import org.bouncycastle.tls.CipherSuite;
import org.bouncycastle.tls.DefaultTlsClient;
import org.bouncycastle.tls.NameType;
import org.bouncycastle.tls.ProtocolVersion;
import org.bouncycastle.tls.SecurityParameters;
import org.bouncycastle.tls.ServerName;
import org.bouncycastle.tls.ServerOnlyTlsAuthentication;
import org.bouncycastle.tls.TlsAuthentication;
import org.bouncycastle.tls.TlsClientProtocol;
import org.bouncycastle.tls.crypto.TlsCrypto;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.security.Principal;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Vector;

import javax.net.ssl.HandshakeCompletedEvent;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * NOTE(2026-10-24 API15 port): Android 4.0.4 系统JSSE只有SSLv3/TLS1.0，无法与TLS1.2+站点握手。
 * 本类用 BouncyCastle 纯轻量 TLS 实现（BcTlsCrypto，不经过平台 JCA/Conscrypt）包一层
 * javax SSLSocketFactory，供 okhttp 直接使用。视频播放不走此链路（ijk 自带 OpenSSL），
 * 本栈只承载配置/接口/EPG 等小流量 HTTPS。
 */
public final class BcTls {

    private static final String TAG = "BcTls";
    private static volatile TlsCrypto sCrypto;
    private static volatile SSLSocketFactory sFactory;

    public static TlsCrypto crypto() {
        if (sCrypto == null) {
            synchronized (BcTls.class) {
                if (sCrypto == null) sCrypto = new BcTlsCrypto(new SecureRandom());
            }
        }
        return sCrypto;
    }

    public static synchronized SSLSocketFactory factory() {
        if (sFactory == null) sFactory = new BcTlsSocketFactory();
        return sFactory;
    }

    public static boolean available() {
        try {
            crypto();
            Class.forName("org.bouncycastle.tls.TlsClientProtocol");
            return true;
        } catch (Throwable th) {
            LOG.e(TAG + " unavailable: " + th);
            return false;
        }
    }

    // ------------------------------------------------------------------ factory

    public static class BcTlsSocketFactory extends SSLSocketFactory {
        @Override
        public SSLSocket createSocket() {
            return new BcTlsSocket(new Socket(), null, -1, true);
        }

        @Override
        public SSLSocket createSocket(String host, int port) throws IOException {
            return new BcTlsSocket(new Socket(host, port), host, port, true);
        }

        @Override
        public SSLSocket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return new BcTlsSocket(new Socket(host, port, localHost, localPort), host, port, true);
        }

        @Override
        public SSLSocket createSocket(InetAddress host, int port) throws IOException {
            return new BcTlsSocket(new Socket(host, port), host.getHostAddress(), port, true);
        }

        @Override
        public SSLSocket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return new BcTlsSocket(new Socket(address, port, localAddress, localPort), address.getHostAddress(), port, true);
        }

        @Override
        public SSLSocket createSocket(Socket socket, String host, int port, boolean autoClose) {
            return new BcTlsSocket(socket, host, port, autoClose);
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return BcTlsSocket.SUPPORTED_CIPHER_SUITES;
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return BcTlsSocket.SUPPORTED_CIPHER_SUITES;
        }
    }

    // ------------------------------------------------------------------ socket

    public static class BcTlsSocket extends SSLSocket {
        static final String[] SUPPORTED_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};
        static final String[] SUPPORTED_CIPHER_SUITES = {
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
                "TLS_RSA_WITH_AES_128_CBC_SHA256",
                "TLS_RSA_WITH_AES_128_CBC_SHA",
                "TLS_RSA_WITH_AES_256_CBC_SHA",
        };

        private final Socket delegate;
        private final String peerHost;
        private final int peerPort;
        private final boolean autoClose;
        private volatile String sniHost;
        private volatile TlsClientProtocol tlsProto;
        private volatile BcTlsSession session;
        private volatile Certificate[] peerCerts;

        BcTlsSocket(Socket delegate, String host, int port, boolean autoClose) {
            this.delegate = delegate;
            this.peerHost = host;
            this.peerPort = port;
            this.autoClose = autoClose;
            this.sniHost = host;
        }

        /** okhttp AndroidPlatform 通过反射调这个设置 SNI */
        public void setHostname(String hostname) {
            if (hostname != null && !hostname.isEmpty()) this.sniHost = hostname;
        }

        @Override
        public void startHandshake() throws IOException {
            if (tlsProto != null) return;
            if (peerHost == null || peerPort <= 0)
                throw new IOException("BcTlsSocket: host/port unknown");
            if (!delegate.isConnected()) delegate.connect(
                    new java.net.InetSocketAddress(peerHost, peerPort), 15000);
            final String hostForCerts = peerHost;
            TlsClientProtocol proto = new TlsClientProtocol(delegate.getInputStream(), delegate.getOutputStream());
            final String[] verHolder = new String[1];
            final int[] csHolder = new int[1];
            DefaultTlsClient client = new DefaultTlsClient(BcTls.crypto()) {
                @Override
                public void notifyHandshakeComplete() throws IOException {
                    super.notifyHandshakeComplete();
                    try {
                        ProtocolVersion v = context.getServerVersion();
                        verHolder[0] = v == null ? "TLSv1.2" : v.toString();
                        SecurityParameters sp = context.getSecurityParameters();
                        csHolder[0] = sp == null ? -1 : sp.getCipherSuite();
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                protected Vector getSNIServerNames() {
                    if (sniHost == null) return null;
                    Vector names = new Vector();
                    names.add(new ServerName(NameType.host_name, sniHost.getBytes()));
                    return names;
                }

                @Override
                public TlsAuthentication getAuthentication() {
                    return new ServerOnlyTlsAuthentication() {
                        @Override
                        public void notifyServerCertificate(org.bouncycastle.tls.TlsServerCertificate serverCertificate) {
                            org.bouncycastle.tls.crypto.TlsCertificate[] chain = serverCertificate.getCertificate().getCertificateList();
                            peerCerts = new Certificate[chain.length];
                            try {
                                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                                for (int i = 0; i < chain.length; i++)
                                    peerCerts[i] = cf.generateCertificate(new java.io.ByteArrayInputStream(chain[i].getEncoded()));
                            } catch (Throwable th) {
                                peerCerts = new Certificate[0];
                            }
                            // 接受所有证书（电视盒子场景，与 SSLSocketFactoryCompat 同口径）
                        }
                    };
                }
            };
            proto.connect(client);
            tlsProto = proto;
            session = new BcTlsSession(
                    verHolder[0] == null ? "TLSv1.2" : verHolder[0].replace("TLS ", "TLSv"),
                    CipherSuiteInfo_name(csHolder[0]),
                    peerCerts == null ? new Certificate[0] : peerCerts,
                    peerHost, peerPort);
            for (HandshakeCompletedListener l : listeners)
                l.handshakeCompleted(new HandshakeCompletedEvent(this, session));
        }

        private static String CipherSuiteInfo_name(int id) {
            // 仅用于展示/记录，okhttp 不据此断言
            switch (id) {
                case CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256: return "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256";
                case CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256: return "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256";
                case CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384: return "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384";
                case CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384: return "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384";
                case CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256: return "TLS_RSA_WITH_AES_128_GCM_SHA256";
                case CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384: return "TLS_RSA_WITH_AES_256_GCM_SHA384";
                case CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA: return "TLS_RSA_WITH_AES_128_CBC_SHA";
                case CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA: return "TLS_RSA_WITH_AES_256_CBC_SHA";
                default: return "TLS_CIPHER_SUITE_0x" + Integer.toHexString(id);
            }
        }

        @Override
        public InputStream getInputStream() throws IOException {
            TlsClientProtocol p = tlsProto;
            return p != null ? p.getInputStream() : delegate.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            TlsClientProtocol p = tlsProto;
            return p != null ? p.getOutputStream() : delegate.getOutputStream();
        }

        @Override
        public SSLSession getSession() {
            return session != null ? session : new BcTlsSession("UNKNOWN", "UNKNOWN", new Certificate[0], peerHost, peerPort);
        }

        @Override
        public void close() throws IOException {
            TlsClientProtocol p = tlsProto;
            if (p != null) try { p.close(); } catch (Throwable ignored) {}
            if (autoClose) delegate.close();
        }

        private final java.util.concurrent.CopyOnWriteArrayList<HandshakeCompletedListener> listeners =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {
            listeners.remove(listener);
        }

        @Override public boolean getUseClientMode() { return true; }
        @Override public void setUseClientMode(boolean mode) { }
        @Override public boolean getNeedClientAuth() { return false; }
        @Override public void setNeedClientAuth(boolean need) { }
        @Override public boolean getWantClientAuth() { return false; }
        @Override public void setWantClientAuth(boolean want) { }
        @Override public boolean getEnableSessionCreation() { return true; }
        @Override public void setEnableSessionCreation(boolean flag) { }
        @Override public String[] getSupportedProtocols() { return SUPPORTED_PROTOCOLS; }
        @Override public String[] getEnabledProtocols() { return SUPPORTED_PROTOCOLS; }
        @Override public void setEnabledProtocols(String[] protocols) { /* BC 栈固定提供 TLS1.2/1.3 */ }
        @Override public String[] getSupportedCipherSuites() { return SUPPORTED_CIPHER_SUITES; }
        @Override public String[] getEnabledCipherSuites() { return SUPPORTED_CIPHER_SUITES; }
        @Override public void setEnabledCipherSuites(String[] suites) { /* 固定套件集，忽略过滤 */ }

        // ---- Socket delegation ----
        @Override public void connect(SocketAddress endpoint) throws IOException { delegate.connect(endpoint); }
        @Override public void connect(SocketAddress endpoint, int timeout) throws IOException { delegate.connect(endpoint, timeout); }
        @Override public void bind(SocketAddress localAddr) throws IOException { delegate.bind(localAddr); }
        @Override public boolean isConnected() { return delegate.isConnected(); }
        @Override public boolean isBound() { return delegate.isBound(); }
        @Override public boolean isClosed() { return delegate.isClosed(); }
        @Override public boolean isInputShutdown() { return delegate.isInputShutdown(); }
        @Override public boolean isOutputShutdown() { return delegate.isOutputShutdown(); }
        @Override public void shutdownInput() throws IOException { delegate.shutdownInput(); }
        @Override public void shutdownOutput() throws IOException { delegate.shutdownOutput(); }
        @Override public void sendUrgentData(int data) throws IOException { delegate.sendUrgentData(data); }
        @Override public InetAddress getInetAddress() { return delegate.getInetAddress(); }
        @Override public InetAddress getLocalAddress() { return delegate.getLocalAddress(); }
        @Override public int getPort() { return delegate.getPort(); }
        @Override public int getLocalPort() { return delegate.getLocalPort(); }
        @Override public SocketAddress getRemoteSocketAddress() { return delegate.getRemoteSocketAddress(); }
        @Override public SocketAddress getLocalSocketAddress() { return delegate.getLocalSocketAddress(); }
        @Override public int getSoTimeout() throws java.net.SocketException { return delegate.getSoTimeout(); }
        @Override public void setSoTimeout(int timeout) throws java.net.SocketException { delegate.setSoTimeout(timeout); }
        @Override public int getSoLinger() throws java.net.SocketException { return delegate.getSoLinger(); }
        @Override public void setSoLinger(boolean on, int linger) throws java.net.SocketException { delegate.setSoLinger(on, linger); }
        @Override public boolean getTcpNoDelay() throws java.net.SocketException { return delegate.getTcpNoDelay(); }
        @Override public void setTcpNoDelay(boolean on) throws java.net.SocketException { delegate.setTcpNoDelay(on); }
        @Override public boolean getKeepAlive() throws java.net.SocketException { return delegate.getKeepAlive(); }
        @Override public void setKeepAlive(boolean on) throws java.net.SocketException { delegate.setKeepAlive(on); }
        @Override public boolean getReuseAddress() throws java.net.SocketException { return delegate.getReuseAddress(); }
        @Override public void setReuseAddress(boolean on) throws java.net.SocketException { delegate.setReuseAddress(on); }
        @Override public boolean getOOBInline() throws java.net.SocketException { return delegate.getOOBInline(); }
        @Override public void setOOBInline(boolean on) throws java.net.SocketException { delegate.setOOBInline(on); }
        @Override public int getTrafficClass() throws java.net.SocketException { return delegate.getTrafficClass(); }
        @Override public void setTrafficClass(int tc) throws java.net.SocketException { delegate.setTrafficClass(tc); }
        @Override public int getSendBufferSize() throws java.net.SocketException { return delegate.getSendBufferSize(); }
        @Override public void setSendBufferSize(int size) throws java.net.SocketException { delegate.setSendBufferSize(size); }
        @Override public int getReceiveBufferSize() throws java.net.SocketException { return delegate.getReceiveBufferSize(); }
        @Override public void setReceiveBufferSize(int size) throws java.net.SocketException { delegate.setReceiveBufferSize(size); }
    }

    // ------------------------------------------------------------------ session

    public static class BcTlsSession implements SSLSession {
        private final String protocol;
        private final String cipherSuite;
        private final Certificate[] peerCerts;
        private final String peerHost;
        private final int peerPort;
        private final long creationTime = System.currentTimeMillis();

        BcTlsSession(String protocol, String cipherSuite, Certificate[] peerCerts, String peerHost, int peerPort) {
            this.protocol = protocol;
            this.cipherSuite = cipherSuite;
            this.peerCerts = peerCerts;
            this.peerHost = peerHost;
            this.peerPort = peerPort;
        }

        @Override public byte[] getId() { return new byte[0]; }
        @Override public SSLSessionContext getSessionContext() { return null; }
        @Override public long getCreationTime() { return creationTime; }
        @Override public long getLastAccessedTime() { return creationTime; }
        @Override public void invalidate() { }
        @Override public boolean isValid() { return true; }
        @Override public void putValue(String name, Object value) { }
        @Override public Object getValue(String name) { return null; }
        @Override public void removeValue(String name) { }
        @Override public String[] getValueNames() { return new String[0]; }
        @Override public Certificate[] getPeerCertificates() { return peerCerts; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public javax.security.cert.X509Certificate[] getPeerCertificateChain() { return null; }
        @Override public Principal getPeerPrincipal() { return null; }
        @Override public Principal getLocalPrincipal() { return null; }
        @Override public String getCipherSuite() { return cipherSuite; }
        @Override public String getProtocol() { return protocol; }
        @Override public String getPeerHost() { return peerHost; }
        @Override public int getPeerPort() { return peerPort; }
        @Override public int getPacketBufferSize() { return 16665; }
        @Override public int getApplicationBufferSize() { return 16640; }
    }
}
