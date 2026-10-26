package org.synesis.cli.daemon;

/**
 * The installation daemon that answered is another build than this command. A daemon starts the project runtimes, so
 * using it would run this command against another build's runtime: the command refuses instead (D8, real run 2026-10-26).
 */
public final class DaemonBuildMismatchException extends java.io.IOException {

    private static final long serialVersionUID = 1L;

    /** The sentence for {@code MESSAGE=}. */
    private final String userMessage;
    /** The step for {@code FIX=}. */
    private final String fix;

    DaemonBuildMismatchException(String daemonBuild, String ownBuild, long daemonPid) {
        super("daemon_build_mismatch");
        this.userMessage = "The Synesis daemon that is running (process " + daemonPid + ") is another build (" + (daemonBuild == null ? "older, unnamed" : daemonBuild) + ") than this command (" + ownBuild + ").";
        this.fix = "stop that daemon (end process " + daemonPid + ", and the project runtimes it started), then repeat; the next command starts one of this build";
    }

    /**
     * One sentence for {@code MESSAGE=}.
     *
     * @return what is different
     */
    public String userMessage() {
        return userMessage;
    }

    /**
     * One step for {@code FIX=}.
     *
     * @return what the person does
     */
    public String fix() {
        return fix;
    }
}
