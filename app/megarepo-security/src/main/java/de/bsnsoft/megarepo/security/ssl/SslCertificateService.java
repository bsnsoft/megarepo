package de.bsnsoft.megarepo.security.ssl;

import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.database.entity.SslCertificateEntity;
import de.bsnsoft.megarepo.database.repository.SslCertificateJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class SslCertificateService {

    private static final Logger log = LoggerFactory.getLogger(SslCertificateService.class);

    private final SslCertificateJpaRepository sslCertificateRepository;

    public SslCertificateService(SslCertificateJpaRepository sslCertificateRepository) {
        this.sslCertificateRepository = sslCertificateRepository;
    }

    public List<SslCertificateEntity> listCertificates() {
        return sslCertificateRepository.findAll();
    }

    @Transactional
    public SslCertificateEntity addCertificateFromPem(String pem) {
        X509Certificate x509 = parsePem(pem);
        String fingerprint = computeFingerprint(x509);

        var existing = sslCertificateRepository.findByFingerprint(fingerprint);
        if (existing.isPresent()) {
            throw new IllegalArgumentException("Certificate with fingerprint " + fingerprint + " already exists");
        }

        SslCertificateEntity entity = new SslCertificateEntity();
        entity.setPem(pem.strip());
        entity.setSubjectCn(extractCn(x509.getSubjectX500Principal().getName()));
        entity.setIssuerCn(extractCn(x509.getIssuerX500Principal().getName()));
        entity.setIssuerOrg(extractField(x509.getIssuerX500Principal().getName(), "O"));
        entity.setFingerprint(fingerprint);
        entity.setIssuedOn(x509.getNotBefore().toInstant());
        entity.setExpiresOn(x509.getNotAfter().toInstant());
        entity.setCreatedAt(Instant.now());

        return sslCertificateRepository.save(entity);
    }

    /**
     * Connect to a remote host via SSL and return the server certificate chain as parsed entities.
     * Does NOT persist them -- caller can inspect and then call addCertificateFromPem to save.
     */
    public List<SslCertificateEntity> fetchCertificatesFromHost(String hostname, int port) {
        try {
            X509Certificate[] chain = fetchCertChain(hostname, port);
            List<SslCertificateEntity> result = new ArrayList<>();
            for (X509Certificate cert : chain) {
                String pem = toPem(cert);
                String fingerprint = computeFingerprint(cert);

                SslCertificateEntity entity = new SslCertificateEntity();
                entity.setPem(pem);
                entity.setSubjectCn(extractCn(cert.getSubjectX500Principal().getName()));
                entity.setIssuerCn(extractCn(cert.getIssuerX500Principal().getName()));
                entity.setIssuerOrg(extractField(cert.getIssuerX500Principal().getName(), "O"));
                entity.setFingerprint(fingerprint);
                entity.setIssuedOn(cert.getNotBefore().toInstant());
                entity.setExpiresOn(cert.getNotAfter().toInstant());
                result.add(entity);
            }
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to fetch certificates from " + hostname + ":" + port + ": " + e.getMessage(), e);
        }
    }

    @Transactional
    public void deleteCertificate(UUID id) {
        if (!sslCertificateRepository.existsById(id)) {
            throw new NotFoundException("SSL certificate not found: " + id);
        }
        sslCertificateRepository.deleteById(id);
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    X509Certificate parsePem(String pem) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            byte[] bytes = pem.strip().getBytes(StandardCharsets.UTF_8);
            return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid PEM certificate: " + e.getMessage(), e);
        }
    }

    String computeFingerprint(X509Certificate cert) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(cert.getEncoded());
            return formatFingerprint(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute certificate fingerprint", e);
        }
    }

    private String formatFingerprint(byte[] digest) {
        String hex = HexFormat.of().withUpperCase().formatHex(digest);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 2) {
            if (!sb.isEmpty()) {
                sb.append(':');
            }
            sb.append(hex, i, i + 2);
        }
        return sb.toString();
    }

    String extractCn(String dn) {
        return extractField(dn, "CN");
    }

    String extractField(String dn, String field) {
        if (dn == null) {
            return null;
        }
        // Parse DN fields - handle escaped commas and quoted values
        String prefix = field + "=";
        int start = dn.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        start += prefix.length();
        int end = dn.indexOf(',', start);
        if (end < 0) {
            end = dn.length();
        }
        return dn.substring(start, end).strip();
    }

    private String toPem(X509Certificate cert) {
        try {
            byte[] encoded = cert.getEncoded();
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded);
            return "-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----";
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode certificate to PEM", e);
        }
    }

    private X509Certificate[] fetchCertChain(String hostname, int port) throws Exception {
        // Create a trust manager that accepts all certificates (for fetching only)
        TrustManager[] trustAllCerts = new TrustManager[] {
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
        SSLSocketFactory factory = sslContext.getSocketFactory();

        try (SSLSocket socket = (SSLSocket) factory.createSocket(hostname, port)) {
            socket.setSoTimeout(10_000);
            socket.startHandshake();
            Certificate[] certs = socket.getSession().getPeerCertificates();
            X509Certificate[] x509Certs = new X509Certificate[certs.length];
            for (int i = 0; i < certs.length; i++) {
                x509Certs[i] = (X509Certificate) certs[i];
            }
            return x509Certs;
        }
    }
}
