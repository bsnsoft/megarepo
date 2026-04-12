package de.bsnsoft.megarepo.app.license;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * One-time utility to generate an RSA 2048-bit keypair for license signing.
 * Run once, embed the public key in {@link LicenseService}, keep the private key at BSNSoft.
 */
public final class LicenseKeyGenerator {

    private LicenseKeyGenerator() {}

    public static void main(String[] args) throws NoSuchAlgorithmException {
        var keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        var keyPair = keyPairGenerator.generateKeyPair();

        var encoder = Base64.getEncoder();
        var publicKeyBase64 = encoder.encodeToString(keyPair.getPublic().getEncoded());
        var privateKeyBase64 = encoder.encodeToString(keyPair.getPrivate().getEncoded());

        System.out.println("=== PUBLIC KEY (embed in LicenseService) ===");
        System.out.println(formatPem("PUBLIC KEY", publicKeyBase64));
        System.out.println();
        System.out.println("=== PRIVATE KEY (keep secret at BSNSoft) ===");
        System.out.println(formatPem("PRIVATE KEY", privateKeyBase64));
    }

    private static String formatPem(String label, String base64) {
        var sb = new StringBuilder();
        sb.append("-----BEGIN ").append(label).append("-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            sb.append(base64, i, Math.min(i + 64, base64.length()));
            sb.append('\n');
        }
        sb.append("-----END ").append(label).append("-----");
        return sb.toString();
    }
}
