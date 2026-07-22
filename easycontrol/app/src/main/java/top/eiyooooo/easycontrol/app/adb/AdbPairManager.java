package top.eiyooooo.easycontrol.app.adb;

import android.annotation.SuppressLint;
import android.os.Build;
import android.sun.misc.BASE64Encoder;
import android.sun.security.provider.X509Factory;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import top.eiyooooo.easycontrol.app.entity.AppData;

/**
 * Android 11+ 无线调试配对管理器。
 *
 * 配对、TLS 和传统 AUTH 必须共用同一把 RSA 密钥，否则部分厂商设备会把它们识别为
 * 两台主机，出现配对成功后仍反复要求授权的问题。
 */
public class AdbPairManager extends AbsAdbConnectionManager {
    private static final String CERTIFICATE_FILE = "pair_cert.pem";
    private static final long CERTIFICATE_VALIDITY_MS = 10L * 365 * 24 * 60 * 60 * 1000;

    public static volatile AdbPairManager INSTANCE;
    public static String keyName;

    private final AdbKeyPair identity;
    private final PrivateKey mPrivateKey;
    private final Certificate mCertificate;

    public static synchronized void init() throws Exception {
        if (AppData.keyPair == null) throw new IOException("ADB identity is unavailable");
        if (INSTANCE == null || !INSTANCE.matches(AppData.keyPair)) {
            if (INSTANCE != null) INSTANCE.disconnect();
            INSTANCE = new AdbPairManager(AppData.keyPair);
        }
        keyName = INSTANCE.getDeviceName();
    }

    private AdbPairManager(@NonNull AdbKeyPair identity) throws Exception {
        setApi(Build.VERSION_CODES.R);
        this.identity = identity;
        this.mPrivateKey = identity.getPrivateKey();
        keyName = getKeyName(identity.getPublicKey());

        Certificate certificate = readCertificateFromFile();
        if (!isCertificateUsable(certificate, identity.getPublicKey())) {
            certificate = generateCertificate(identity.getPublicKey(), mPrivateKey, keyName);
            writeCertificateToFile(certificate);
        }
        this.mCertificate = certificate;
    }

    private boolean matches(AdbKeyPair candidate) {
        return Arrays.equals(identity.getPublicKey().getEncoded(), candidate.getPublicKey().getEncoded());
    }

    /** Regenerates the one shared identity used by AUTH, pairing and TLS. */
    public static synchronized void regenerateKey() throws Exception {
        if (INSTANCE != null) INSTANCE.disconnect();
        for (Adb adb : Adb.adbMap.values().toArray(new Adb[0])) adb.close();
        AppData.reGenerateAdbKeyPair(AppData.main);
        File certFile = new File(AppData.main.getFilesDir(), CERTIFICATE_FILE);
        if (certFile.exists() && !certFile.delete()) {
            throw new IOException("Unable to remove old ADB pairing certificate");
        }
        INSTANCE = null;
        init();
    }

    /** Builds the mutual-TLS context used after the ADB STLS exchange. */
    @SuppressLint("TrustAllX509TrustManager")
    @NonNull
    public static synchronized SSLContext createSslContext(@NonNull AdbKeyPair identity)
            throws Exception {
        if (INSTANCE == null || !INSTANCE.matches(identity)) {
            if (INSTANCE != null) INSTANCE.disconnect();
            INSTANCE = new AdbPairManager(identity);
        }
        keyName = INSTANCE.getDeviceName();

        SSLContext sslContext;
        try {
            sslContext = SSLContext.getInstance("TLSv1.3");
        } catch (NoSuchAlgorithmException e) {
            throw new NoSuchAlgorithmException(
                    "ADB wireless debugging requires TLS 1.3 on this head unit", e);
        }
        sslContext.init(new KeyManager[]{createKeyManager(INSTANCE.mPrivateKey,
                        (X509Certificate) INSTANCE.mCertificate)},
                new X509TrustManager[]{createAdbTrustManager()}, new SecureRandom());
        return sslContext;
    }

    @NonNull
    private static X509KeyManager createKeyManager(@NonNull PrivateKey privateKey,
                                                    @NonNull X509Certificate certificate) {
        return new X509KeyManager() {
            private static final String ALIAS = "easycontrol-adb";

            @Override
            public String[] getClientAliases(String keyType, Principal[] issuers) {
                return "RSA".equals(keyType) ? new String[]{ALIAS} : null;
            }

            @Override
            public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
                if (keyTypes != null) {
                    for (String keyType : keyTypes) if ("RSA".equals(keyType)) return ALIAS;
                }
                return null;
            }

            @Override
            public String[] getServerAliases(String keyType, Principal[] issuers) {
                return null;
            }

            @Override
            public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                return null;
            }

            @Override
            public X509Certificate[] getCertificateChain(String alias) {
                return ALIAS.equals(alias) ? new X509Certificate[]{certificate} : null;
            }

            @Override
            public PrivateKey getPrivateKey(String alias) {
                return ALIAS.equals(alias) ? privateKey : null;
            }
        };
    }

    @SuppressLint("TrustAllX509TrustManager")
    @NonNull
    private static X509TrustManager createAdbTrustManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    @NonNull
    @Override
    protected PrivateKey getPrivateKey() {
        return mPrivateKey;
    }

    @NonNull
    @Override
    protected Certificate getCertificate() {
        return mCertificate;
    }

    @NonNull
    @Override
    protected String getDeviceName() {
        return keyName;
    }

    /** Do not destroy the shared private key when closing only the pairing helper. */
    @Override
    public void close() throws IOException {
        disconnect();
    }

    private static boolean isCertificateUsable(@Nullable Certificate certificate,
                                                @NonNull PublicKey publicKey) {
        if (!(certificate instanceof X509Certificate)) return false;
        X509Certificate x509Certificate = (X509Certificate) certificate;
        try {
            x509Certificate.checkValidity();
            return Arrays.equals(x509Certificate.getPublicKey().getEncoded(), publicKey.getEncoded());
        } catch (Exception ignored) {
            return false;
        }
    }

    @NonNull
    private static Certificate generateCertificate(@NonNull PublicKey publicKey,
                                                   @NonNull PrivateKey privateKey,
                                                   @NonNull String certificateName) throws Exception {
        String subject = "CN=" + certificateName;
        String algorithmName = "SHA512withRSA";
        Date notBefore = new Date(System.currentTimeMillis() - 60_000);
        Date notAfter = new Date(System.currentTimeMillis() + CERTIFICATE_VALIDITY_MS);

        CertificateExtensions certificateExtensions = new CertificateExtensions();
        certificateExtensions.set("SubjectKeyIdentifier", new SubjectKeyIdentifierExtension(
                new KeyIdentifier(publicKey).getIdentifier()));
        certificateExtensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));
        X500Name x500Name = new X500Name(subject);
        CertificateValidity certificateValidity = new CertificateValidity(notBefore, notAfter);
        X509CertInfo x509CertInfo = new X509CertInfo();
        x509CertInfo.set("version", new CertificateVersion(2));
        x509CertInfo.set("serialNumber",
                new CertificateSerialNumber(new SecureRandom().nextInt() & Integer.MAX_VALUE));
        x509CertInfo.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithmName)));
        x509CertInfo.set("subject", new CertificateSubjectName(x500Name));
        x509CertInfo.set("key", new CertificateX509Key(publicKey));
        x509CertInfo.set("validity", certificateValidity);
        x509CertInfo.set("issuer", new CertificateIssuerName(x500Name));
        x509CertInfo.set("extensions", certificateExtensions);
        X509CertImpl certificate = new X509CertImpl(x509CertInfo);
        certificate.sign(privateKey, algorithmName);
        return certificate;
    }

    @Nullable
    private static Certificate readCertificateFromFile() throws IOException, CertificateException {
        File certFile = new File(AppData.main.getFilesDir(), CERTIFICATE_FILE);
        if (!certFile.exists()) return null;
        try (FileInputStream cert = new FileInputStream(certFile)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(cert);
        }
    }

    private static void writeCertificateToFile(@NonNull Certificate certificate)
            throws CertificateEncodingException, IOException {
        File certFile = new File(AppData.main.getFilesDir(), CERTIFICATE_FILE);
        BASE64Encoder encoder = new BASE64Encoder();
        try (FileOutputStream os = new FileOutputStream(certFile)) {
            os.write(X509Factory.BEGIN_CERT.getBytes(StandardCharsets.UTF_8));
            os.write('\n');
            encoder.encode(certificate.getEncoded(), os);
            os.write('\n');
            os.write(X509Factory.END_CERT.getBytes(StandardCharsets.UTF_8));
        }
    }

    @NonNull
    private static String getKeyName(@NonNull PublicKey publicKey) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded());
        StringBuilder suffix = new StringBuilder(8);
        for (int i = digest.length - 4; i < digest.length; i++) {
            suffix.append(String.format(java.util.Locale.US, "%02x", digest[i] & 0xff));
        }
        return "Easycontrol_For_Car-" + suffix;
    }
}
