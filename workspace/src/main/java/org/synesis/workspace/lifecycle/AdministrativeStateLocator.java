package org.synesis.workspace.lifecycle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Resolves Synesis administrative state from the canonical Git common
 * directory rather than from a mutable worktree path or a project UUID.
 *
 * <p>The locator deliberately keeps administrative journals outside the
 * control checkout. A linked worktree and its main checkout therefore share
 * one administrative identity, while unrelated repositories remain isolated.
 * The returned paths are ordinary local paths; this class does not create a
 * daemon, service, or database.</p>
 */
public final class AdministrativeStateLocator {

    private static final String APPLICATION_DIRECTORY = "Synesis";
    private final Path stateRoot;

    /** Creates a locator using the current host environment. */
    public AdministrativeStateLocator() {
        this(applicationStateRoot());
    }

    /**
     * Creates a locator rooted at an explicit local state directory.
     *
     * @param stateRoot local state directory used for repository administration
     */
    public AdministrativeStateLocator(Path stateRoot) {
        this.stateRoot = normalize(stateRoot, "stateRoot");
    }

    /**
     * Resolves the canonical administrative state for a repository.
     *
     * @param repositoryRoot repository worktree or checkout
     * @return resolved repository identity and administrative paths
     * @throws IOException when Git cannot resolve the common directory
     */
    public Resolution resolve(Path repositoryRoot) throws IOException {
        Path root = normalize(repositoryRoot, "repositoryRoot");
        Path common = resolveGitCommonDirectory(root);
        String identity = identity(common);
        Path admin = stateRoot.resolve("repositories").resolve(identity).resolve("admin");
        return new Resolution(root, common, identity, admin,
                admin.resolve("baseline"), admin.resolve("reset"), admin.resolve("index"));
    }

    /**
     * Resolves the canonical Git common directory for a repository.
     *
     * @param repositoryRoot repository worktree or checkout
     * @return canonical Git common directory
     * @throws IOException when the repository is not a valid Git checkout
     */
    public Path resolveGitCommonDirectory(Path repositoryRoot) throws IOException {
        Path root = normalize(repositoryRoot, "repositoryRoot");
        ProcessBuilder builder = new ProcessBuilder("git", "rev-parse", "--path-format=absolute", "--git-common-dir");
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exit = process.waitFor();
            if (exit == 0 && !output.isBlank()) {
                return canonicalPath(root.resolve(output));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("git common-directory resolution interrupted", interrupted);
        }
        Path dotGit = root.resolve(".git");
        if (Files.isDirectory(dotGit)) {
            return canonicalPath(dotGit);
        }
        if (Files.isRegularFile(dotGit)) {
            String content = Files.readString(dotGit, StandardCharsets.UTF_8).trim();
            if (content.startsWith("gitdir:")) {
                Path gitDir = root.resolve(content.substring("gitdir:".length()).trim());
                return canonicalPath(gitDir);
            }
        }
        throw new IOException("GIT_COMMON_DIRECTORY_UNAVAILABLE");
    }

    /**
     * Derives a stable repository identity from a canonical common directory.
     *
     * @param commonDirectory canonical Git common directory
     * @return lower-case SHA-256 identity
     */
    public static String identity(Path commonDirectory) {
        Objects.requireNonNull(commonDirectory, "commonDirectory");
        String canonical = canonicalPath(commonDirectory).toString().replace('\\', '/');
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("repository identity hashing unavailable", failure);
        }
    }

    /**
     * Returns the host-appropriate local Synesis state root.
     *
     * @return local state root
     */
    public static Path applicationStateRoot() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String base;
        if (os.contains("win")) {
            base = System.getenv("LOCALAPPDATA");
            if (base == null || base.isBlank()) {
                base = Path.of(System.getProperty("user.home"), "AppData", "Local").toString();
            }
        } else if (os.contains("mac")) {
            base = Path.of(System.getProperty("user.home"), "Library", "Application Support").toString();
        } else {
            base = System.getenv("XDG_STATE_HOME");
            if (base == null || base.isBlank()) {
                base = Path.of(System.getProperty("user.home"), ".local", "state").toString();
            }
        }
        return Path.of(base).toAbsolutePath().normalize().resolve(APPLICATION_DIRECTORY);
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }

    private static Path canonicalPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException unavailable) {
            return path.toAbsolutePath().normalize();
        }
    }

    /**
     * Resolved administrative identity and journal roots.
     *
     * @param repositoryRoot requested repository root
     * @param commonDirectory canonical Git common directory
     * @param repositoryIdentity stable common-directory identity
     * @param administrativeRoot external Synesis administrative root
     * @param baselineRoot baseline transaction state
     * @param resetRoot reset transaction state
     * @param indexRoot real-index synchronization state
     */
    public record Resolution(Path repositoryRoot, Path commonDirectory, String repositoryIdentity,
                             Path administrativeRoot, Path baselineRoot, Path resetRoot, Path indexRoot) {
        /** Validates and normalizes the resolved paths. */
        public Resolution {
            repositoryRoot = normalize(repositoryRoot, "repositoryRoot");
            commonDirectory = normalize(commonDirectory, "commonDirectory");
            Objects.requireNonNull(repositoryIdentity, "repositoryIdentity");
            administrativeRoot = normalize(administrativeRoot, "administrativeRoot");
            baselineRoot = normalize(baselineRoot, "baselineRoot");
            resetRoot = normalize(resetRoot, "resetRoot");
            indexRoot = normalize(indexRoot, "indexRoot");
        }
    }
}
