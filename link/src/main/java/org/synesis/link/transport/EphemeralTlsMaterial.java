package org.synesis.link.transport;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * Creates short-lived transport-only TLS material outside the identity store.
 */
final class EphemeralTlsMaterial implements AutoCloseable {
    private static final String PASSWORD = "synesis-ephemeral";
    final Path directory;
    final PrivateKey key;
    final X509Certificate certificate;

    private EphemeralTlsMaterial(Path directory, PrivateKey key, X509Certificate certificate) {
        this.directory = directory;
        this.key = key;
        this.certificate = certificate;
    }

    static EphemeralTlsMaterial create() throws Exception {
        Path directory = Files.createTempDirectory("synesis-link-tls");
        try {
            Path store = directory.resolve("transport.p12");
            String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                    ? "keytool.exe" : "keytool";
            String keytool = Path.of(System.getProperty("java.home"), "bin", executable).toString();
            Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "quic", "-keyalg", "RSA",
                    "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD,
                    "-keypass", PASSWORD, "-dname", "CN=synesis-link", "-validity", "1", "-noprompt")
                    .redirectErrorStream(true).start();
            if (process.waitFor() != 0) throw new IllegalStateException("ephemeral TLS generation failed");
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(store)) {
                keyStore.load(input, PASSWORD.toCharArray());
            }
            return new EphemeralTlsMaterial(directory, (PrivateKey) keyStore.getKey("quic", PASSWORD.toCharArray()),
                    (X509Certificate) keyStore.getCertificate("quic"));
        } catch (Exception failure) {
            delete(directory);
            throw failure;
        }
    }

    @Override
    public void close() throws java.io.IOException {
        delete(directory);
    }

    private static void delete(Path directory) throws java.io.IOException {
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            });
        } catch (java.io.UncheckedIOException exception) {
            throw exception.getCause();
        }
    }
}
