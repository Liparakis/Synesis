package org.synesis.cli.daemon;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.synesis.link.protocol.LinkCompatibility;

/**
 * Reads the build metadata embedded in the application bundle.
 */
public final class BuildInfo {

    private BuildInfo() {
    }

    /**
     * Names this build for comparing it with another process of the same installation: version, commit and build time.
     * Two processes that report different identities are different builds and must not serve each other.
     *
     * @return the build identity
     */
    public static String identity() {
        Properties values = properties();
        return values.getProperty("version", "0.1.0-dev.local") + " " + values.getProperty("commit", "UNKNOWN") + " " + values.getProperty("time", "UNKNOWN");
    }

    /**
     * Returns this build's version.
     *
     * @return the version, or a development placeholder
     */
    public static String version() {
        return properties().getProperty("version", "0.1.0-dev.local");
    }

    /**
     * Returns the text {@code synesis version} prints.
     *
     * @return version, commit and build time
     */
    public static String text() {
        Properties values = properties();
        return "SYNESIS_VERSION=" + values.getProperty("version", "0.1.0-dev.local") + System.lineSeparator() + "RECORD_FORMAT=" + values.getProperty("recordFormat", "SDR2") + System.lineSeparator()
                + "RECONCILIATION_PROTOCOL=" + values.getProperty("reconciliationProtocol", "PRP1") + System.lineSeparator()
                + "LINK_COMPATIBILITY=" + LinkCompatibility.CURRENT + System.lineSeparator() + "BUILD_COMMIT=" + values.getProperty("commit", "UNKNOWN") + System.lineSeparator()
                + "BUILD_TIME=" + values.getProperty("time", "UNKNOWN") + System.lineSeparator() + "BUILD_PLATFORM=" + values.getProperty("platform", "UNKNOWN") + System.lineSeparator() + "JAVA_RUNTIME="
                + values.getProperty("javaRuntime", Runtime.version().toString());
    }

    private static Properties properties() {
        Properties values = new Properties();
        try (InputStream input = BuildInfo.class.getResourceAsStream("/synesis-build.properties")) {
            if (input != null) {
                values.load(input);
            }
        } catch (IOException ignored) {
            // Defaults keep diagnostics usable if an unbundled development classpath is used.
        }
        return values;
    }
}
