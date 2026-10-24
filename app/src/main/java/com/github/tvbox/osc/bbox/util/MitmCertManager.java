package com.github.tvbox.osc.bbox.util;

import android.content.Context;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * NOTE(2026-10-25 API15 port): jar \u805a\u5408\u6e90\u7684 https \u89e3\u5c01 \u2014\u2014 \u8bc1\u4e66\u5c42
 * \u751f\u6210\u81ea\u7b7e CA + \u6309\u57df\u540d\u52a8\u6001\u7b7e\u53d1\u53f6\u5b50\u8bc1\u4e66\uff1bCA \u5bfc\u51fa\u540e\u7531 adb \u585e\u8fdb
 * /data/misc/keychain/cacerts-added/ \uff08jar \u7684 okhttp \u9ed8\u8ba4\u4fe1\u4efb\u5e93\u4f1a\u8bfb\u5b83\uff09\u3002
 * \u642d\u914d MitmProxy \uff08TLS1.0 \u670d\u52a1\u7aef\u63a5 jar + BC TLS1.2 \u4e0a\u6e38\uff09\u5b9e\u73b0\u786c\u89e3\u65e0\u5173\u7684 https \u5c01\u3002
 */
public class MitmCertManager {

    private static final String TAG = "MitmCertManager";
    private static final Object LOCK = new Object();
    private static KeyPair caKeyPair;
    private static X509Certificate caCert;
    private static final Map<String, X509Certificate[]> leafCache = new HashMap<>();
    private static final Map<String, KeyManagerFactory> kmfCache = new HashMap<>();
    private static File exportDir;

    public static void init(Context ctx) {
        synchronized (LOCK) {
            if (caCert != null) return;
            try {
                exportDir = ctx.getFilesDir();
                File caFile = new File(exportDir, "mitm_ca.crt");
                File keyFile = new File(exportDir, "mitm_ca.key");
                if (caFile.exists() && keyFile.exists()) {
                    caCert = loadCert(caFile);
                    caKeyPair = loadKeyPair(keyFile);
                    LOG.i(TAG + ": CA loaded");
                } else {
                    generate();
                    save(caFile, keyFile);
                    LOG.i(TAG + ": CA generated, export " + caFile.getAbsolutePath() + " hash=" + caStoreFileName());
                }
            } catch (Throwable th) {
                LOG.e(TAG + " init failed: " + th);
                java.io.StringWriter sw = new java.io.StringWriter();
                th.printStackTrace(new java.io.PrintWriter(sw));
                for (String l : sw.toString().split("\n")) LOG.e("MITMSTACK " + l);
            }
        }
    }

    /** CA \u8bc1\u4e66\u6587\u4ef6\u540d\uff08android subject_hash_old \u683c\u5f0f\uff0c\u4f9b cacerts-added \u4f7f\u7528\uff09 */
    public static String caStoreFileName() {
        try {
            if (caCert == null) return null;
            byte[] subject = caCert.getSubjectX500Principal().getEncoded();
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(subject);
            // openssl subject_hash_old: \u524d 4 \u5b57\u8282\u5c0f\u7aef\u5e8f
            int v = ((digest[0] & 0xFF) << 24) | ((digest[1] & 0xFF) << 16) | ((digest[2] & 0xFF) << 8) | (digest[3] & 0xFF);
            return String.format("%08x", v) + ".0";
        } catch (Throwable th) {
            return null;
        }
    }

    public static File caExportFile() {
        return caCert == null ? null : new File(exportDir, "mitm_ca.crt");
    }

    /** \u83b7\u53d6\u6307\u5b9a\u57df\u540d\u7684 KeyManager\uff08\u52a8\u6001\u53f6\u5b50\u8bc1\u4e66\uff0c\u5e26 CA \u94fe\uff09 */
    public static KeyManagerFactory keyManagerFor(String host) {
        synchronized (LOCK) {
            try {
                KeyManagerFactory cached = kmfCache.get(host);
                if (cached != null) return cached;
                if (caCert == null || caKeyPair == null) return null;
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048);
                KeyPair leafKey = kpg.generateKeyPair();
                long now = System.currentTimeMillis();
                X500Name issuer = new X500Name("CN=TVBox MITM CA");
                org.bouncycastle.asn1.x500.X500Name subjectName = new X500Name("CN=" + host);
                JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                        issuer, BigInteger.valueOf(now),
                        new Date(now - 86400000L), new Date(now + 90L * 86400000L),
                        subjectName, leafKey.getPublic());
                builder.addExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName, false,
                        new GeneralNames(new GeneralName(GeneralName.dNSName, host)));
                builder.addExtension(org.bouncycastle.asn1.x509.Extension.keyUsage, true,
                        new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
                builder.addExtension(org.bouncycastle.asn1.x509.Extension.extendedKeyUsage, false,
                        new ExtendedKeyUsage(new KeyPurposeId[]{KeyPurposeId.id_kp_serverAuth}));
                ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                        .setProvider("BC").build(leafKey.getPrivate());
                X509Certificate leaf = new JcaX509CertificateConverter().setProvider("BC")
                        .getCertificate(builder.build(signer));
                // \u5185\u5b58 keystore\uff1aleaf + CA \u94fe
                KeyStore ks = KeyStore.getInstance("PKCS12");
                ks.load(null, null);
                ks.setKeyEntry("leaf", leafKey.getPrivate(), new char[0],
                        new X509Certificate[]{leaf, caCert});
                KeyManagerFactory kmf = KeyManagerFactory.getInstance("X509");
                kmf.init(ks, new char[0]);
                if (kmfCache.size() > 64) kmfCache.clear();
                kmfCache.put(host, kmf);
                return kmf;
            } catch (Throwable th) {
                LOG.e(TAG + " keyManagerFor " + host + " failed: " + th);
                return null;
            }
        }
    }

    /** \u4e0a\u6e38\u9a8c\u8bc1\u4fe1\u4efb\u5e93\uff1a\u8fd4\u56de CA \u4f5c\u4e3a\u552f\u4e00\u951a\u70b9\uff08\u4e2d\u95f4\u4eba\u8bc1\u4e66\u53ef\u88ab\u672c\u4ee3\u7406\u4f53\u7cfb\u81ea\u4fe1\uff09 */
    public static X509Certificate[] caAsAnchor() {
        return caCert == null ? new X509Certificate[0] : new X509Certificate[]{caCert};
    }

    private static void generate() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        caKeyPair = kpg.generateKeyPair();
        long now = System.currentTimeMillis();
        X500Name subject = new X500Name("CN=TVBox MITM CA");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(now),
                new Date(now - 86400000L), new Date(now + 3650L * 86400000L),
                subject, caKeyPair.getPublic());
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.keyUsage, true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.digitalSignature | KeyUsage.cRLSign));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(caKeyPair.getPrivate());
        caCert = new JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer));
        caCert.verify(caKeyPair.getPublic());
    }

    private static void save(File caFile, File keyFile) throws Exception {
        FileOutputStream fos = new FileOutputStream(caFile);
        fos.write(caCert.getEncoded());
        fos.close();
        // \u79c1\u94a5 PKCS8 DER\uff08\u91cd\u542f\u540e\u91cd\u8f7d\u7528\uff09
        org.bouncycastle.asn1.pkcs.PrivateKeyInfo pki =
                org.bouncycastle.asn1.pkcs.PrivateKeyInfo.getInstance(caKeyPair.getPrivate().getEncoded());
        FileOutputStream kfos = new FileOutputStream(keyFile);
        kfos.write(pki.getEncoded());
        kfos.close();
    }

    private static X509Certificate loadCert(File f) throws Exception {
        java.io.InputStream is = new java.io.FileInputStream(f);
        byte[] der = new byte[is.available()];
        is.read(der);
        is.close();
        java.security.cert.CertificateFactory cf =
                java.security.cert.CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(der));
    }

    private static KeyPair loadKeyPair(File f) throws Exception {
        java.io.InputStream is = new java.io.FileInputStream(f);
        byte[] der = new byte[is.available()];
        is.read(der);
        is.close();
        org.bouncycastle.asn1.pkcs.PrivateKeyInfo pki =
                org.bouncycastle.asn1.pkcs.PrivateKeyInfo.getInstance(der);
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
        java.security.PublicKey pub = kf.generatePublic(
                new java.security.spec.X509EncodedKeySpec(pki.getPublicKeyData().getBytes()));
        java.security.PrivateKey priv = kf.generatePrivate(
                new java.security.spec.PKCS8EncodedKeySpec(der));
        return new KeyPair(pub, priv);
    }
}
