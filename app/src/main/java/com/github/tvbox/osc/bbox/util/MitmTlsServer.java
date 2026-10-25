package com.github.tvbox.osc.bbox.util;

import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.tls.Certificate;
import org.bouncycastle.tls.DefaultTlsServer;
import org.bouncycastle.tls.HashAlgorithm;
import org.bouncycastle.tls.SignatureAlgorithm;
import org.bouncycastle.tls.SignatureAndHashAlgorithm;
import org.bouncycastle.tls.crypto.TlsCryptoParameters;
import org.bouncycastle.tls.TlsCredentialedDecryptor;
import org.bouncycastle.tls.TlsCredentialedSigner;
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedDecryptor;
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * MITM \u7684 TLS \u670d\u52a1\u7aef \u2014\u2014 \u7eaf BC \u8f7b\u91cf\u5b9e\u73b0\uff0c\u63a5\u53d7 jar \u5ba2\u6237\u7aef\u7684 TLS1.0 \u8fde\u63a5\u3002
 */
public class MitmTlsServer extends DefaultTlsServer {

    private final X509Certificate leaf;
    private final X509Certificate ca;
    private final AsymmetricKeyParameter leafPriv;
    private final BcTlsCrypto bcCrypto;

    public MitmTlsServer(BcTlsCrypto crypto, X509Certificate leaf, X509Certificate ca, AsymmetricKeyParameter leafPriv) {
        super(crypto);
        this.bcCrypto = crypto;
        this.leaf = leaf;
        this.ca = ca;
        this.leafPriv = leafPriv;
    }

    private Certificate chain() throws java.io.IOException {
        try {
            byte[] leafDer = leaf.getEncoded();
            byte[] caDer = ca.getEncoded();
            return new Certificate(new org.bouncycastle.tls.crypto.TlsCertificate[]{
                    bcCrypto.createCertificate(leafDer),
                    bcCrypto.createCertificate(caDer)});
        } catch (java.io.IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new java.io.IOException(t);
        }
    }


    @Override
    public org.bouncycastle.tls.ProtocolVersion[] getProtocolVersions() {
        // 接受 TLS1.0-1.2 连接（jar 客户端只会 TLS1.0）
        return new org.bouncycastle.tls.ProtocolVersion[]{
                org.bouncycastle.tls.ProtocolVersion.TLSv12,
                org.bouncycastle.tls.ProtocolVersion.TLSv11,
                org.bouncycastle.tls.ProtocolVersion.TLSv10};
    }

    @Override
    protected TlsCredentialedSigner getRSASignerCredentials() throws java.io.IOException {
        try {
            TlsCryptoParameters cryptoParams = new TlsCryptoParameters(context);
            SignatureAndHashAlgorithm sigAlg = SignatureAndHashAlgorithm.getInstance(
                    HashAlgorithm.sha256, SignatureAlgorithm.rsa);
            return new BcDefaultTlsCredentialedSigner(cryptoParams, bcCrypto,
                    leafPriv, chain(), sigAlg);
        } catch (java.io.IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Override
    protected TlsCredentialedDecryptor getRSAEncryptionCredentials() throws java.io.IOException {
        try {
            return new BcDefaultTlsCredentialedDecryptor(bcCrypto, chain(), leafPriv);
        } catch (java.io.IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
