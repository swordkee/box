package com.github.tvbox.osc.bbox.util;

import android.content.Context;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * NOTE(2026-10-25 API15 port): MITM \u8bc1\u4e66\u7ba1\u7406\u2014\u2014 jar \u805a\u5408\u6e90 https \u89e3\u5c01
 * \u81ea\u7b7e CA + \u6309\u57df\u540d\u52a8\u6001\u7b7e\u53d1\u53f6\u5b50\u8bc1\u4e66\u3002
 * CA \u5bfc\u51fa\u540e\u7531 adb \u585e\u8fdb /data/misc/keychain/cacerts-added/\uff0c
 * jar \u7684 okhttp \u9ed8\u8ba4\u4fe1\u4efb\u5e93\u4f1a\u8bfb\u5b83\u3002
 * \u6301\u4e45\u5316\uff1a\u88f8 DER \u6587\u4ef6\uff08\u5e73\u53f0 CertificateFactory/KeyFactory \u89e3\u6790\uff0c\u4e0d\u7528 KeyStore\uff09\u3002
 */
public class MitmCertManager {

    private static final String TAG = "MitmCertManager";
    private static final Object LOCK = new Object();
    private static KeyPair caKeyPair;
    private static X509Certificate caCert;
    private static final Map<String, Leaf> leafCache = new HashMap<>();
    private static File exportDir;

    public static class Leaf {
        public final X509Certificate cert;
        public final AsymmetricKeyParameter priv;
        public Leaf(X509Certificate c, AsymmetricKeyParameter p) { cert = c; priv = p; }
    }

    public static void init(Context ctx) {
        synchronized (LOCK) {
            if (caCert != null) return;
            try {
                exportDir = ctx.getFilesDir();
                File caFile = new File(exportDir, "mitm_ca.crt");
                File keyFile = new File(exportDir, "mitm_ca.key");
                if (caFile.exists() && keyFile.exists()) {
                    try {
                        caCert = parseCert(readAll(caFile));
                        caKeyPair = new KeyPair(caCert.getPublicKey(), parsePriv(readAll(keyFile)));
                        LOG.i(TAG + ": CA loaded from disk, hash=" + caStoreFileName());
                        return;
                    } catch (Throwable th) {
                        LOG.e(TAG + ": CA load failed (" + th + "), regenerating");
                        caCert = null;
                        caKeyPair = null;
                    }
                }
                // \u751f\u6210\u65b0 CA
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
                // \u4fdd\u5b58
                FileOutputStream cfos = new FileOutputStream(caFile);
                cfos.write(caCert.getEncoded());
                cfos.close();
                FileOutputStream kfos = new FileOutputStream(keyFile);
                kfos.write(caKeyPair.getPrivate().getEncoded());
                kfos.close();
                LOG.i(TAG + ": CA generated, hash=" + caStoreFileName());
            } catch (Throwable th) {
                LOG.e(TAG + " init failed: " + th);
                java.io.StringWriter sw = new java.io.StringWriter();
                th.printStackTrace(new java.io.PrintWriter(sw));
                for (String l : sw.toString().split("\n")) LOG.e("MITMSTACK " + l);
            }
        }
    }

    public static Leaf leafFor(String host) {
        synchronized (LOCK) {
            try {
                if (caCert == null || caKeyPair == null) return null;
                Leaf cached = leafCache.get(host);
                if (cached != null) return cached;
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048);
                KeyPair leafKey = kpg.generateKeyPair();
                long now = System.currentTimeMillis();
                X500Name issuer = new X500Name("CN=TVBox MITM CA");
                X500Name subjectName = new X500Name("CN=" + host);
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
                        .setProvider("BC").build(caKeyPair.getPrivate());
                X509Certificate leaf = new JcaX509CertificateConverter().setProvider("BC")
                        .getCertificate(builder.build(signer));
                AsymmetricKeyParameter priv = org.bouncycastle.crypto.util.PrivateKeyFactory
                        .createKey(leafKey.getPrivate().getEncoded());
                Leaf l = new Leaf(leaf, priv);
                if (leafCache.size() > 64) leafCache.clear();
                leafCache.put(host, l);
                return l;
            } catch (Throwable th) {
                LOG.e(TAG + " leafFor " + host + " failed: " + th);
                return null;
            }
        }
    }

    /** android subject_hash_old\uff1aMD5(subject DER) \u524d 4 \u5b57\u8282\u5c0f\u7aef\u5e8f */
    public static String caStoreFileName() {
        try {
            if (caCert == null) return null;
            byte[] subject = caCert.getSubjectX500Principal().getEncoded();
            byte[] digest = java.security.MessageDigest.getInstance("MD5").digest(subject);
            int v = ((digest[0] & 0xFF) << 24) | ((digest[1] & 0xFF) << 16) | ((digest[2] & 0xFF) << 8) | (digest[3] & 0xFF);
            return String.format("%08x", v);
        } catch (Throwable th) {
            return null;
        }
    }

    public static X509Certificate getCaCert() { return caCert; }
    public static KeyPair getCaKeyPair() { return caKeyPair; }

    public static File caExportFile() {
        return caCert == null ? null : new File(exportDir, "mitm_ca.crt");
    }

    private static byte[] readAll(File f) throws Exception {
        java.io.FileInputStream is = new FileInputStream(f);
        byte[] data = new byte[is.available()];
        is.read(data);
        is.close();
        return data;
    }

    private static X509Certificate parseCert(byte[] der) throws Exception {
        java.security.cert.CertificateFactory cf =
                java.security.cert.CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(der));
    }

    private static java.security.PrivateKey parsePriv(byte[] der) throws Exception {
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
        return kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
    }
}
