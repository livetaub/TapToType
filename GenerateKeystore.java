import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.*;
import java.security.cert.Certificate;
import java.util.Date;
import sun.security.x509.*;

public class GenerateKeystore {
    public static void main(String[] args) throws Exception {
        String keystorePath = args.length > 0 ? args[0] : "taptotype-release-key.jks";
        String storePass = "changeme123";
        String keyPass = "changeme123";
        String alias = "taptotype";

        // Generate RSA key pair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        // Create self-signed certificate
        X500Name owner = new X500Name("CN=TapToType, OU=Development, O=TapToType, L=Unknown, ST=Unknown, C=US");
        long validity = 10000L * 24 * 60 * 60 * 1000; // 10000 days
        Date from = new Date();
        Date to = new Date(from.getTime() + validity);

        CertificateValidity interval = new CertificateValidity(from, to);
        BigInteger sn = new BigInteger(64, new java.security.SecureRandom());

        X509CertInfo info = new X509CertInfo();
        info.set(X509CertInfo.VALIDITY, interval);
        info.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(sn));
        info.set(X509CertInfo.SUBJECT, owner);
        info.set(X509CertInfo.ISSUER, owner);
        info.set(X509CertInfo.KEY, new CertificateX509Key(kp.getPublic()));
        info.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3));
        AlgorithmId algo = new AlgorithmId(AlgorithmId.sha256WithRSAEncryption_oid);
        info.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(algo));

        X509CertImpl cert = new X509CertImpl(info);
        cert.sign(kp.getPrivate(), "SHA256withRSA");

        // Store in keystore
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, storePass.toCharArray());
        ks.setKeyEntry(alias, kp.getPrivate(), keyPass.toCharArray(), new Certificate[]{cert});

        try (FileOutputStream fos = new FileOutputStream(keystorePath)) {
            ks.store(fos, storePass.toCharArray());
        }

        System.out.println("SUCCESS: Keystore created at " + keystorePath);
        System.out.println("Alias: " + alias);
        System.out.println("Valid for 10000 days");
    }
}
