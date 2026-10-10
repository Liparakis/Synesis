package org.synesis.workspace.infrastructure.filesystem;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the canonical project-local Synesis metadata root and its local
 * presentation policy.
 *
 * <p>This class deliberately knows nothing about project identity,
 * membership, authorization, Git tracking, or user project files. It only
 * validates/creates {@code .synesis} and best-effort applies the standard
 * Windows Hidden attribute to that directory. Unix-like systems have no
 * additional policy because the leading dot already supplies the intended
 * presentation behavior.</p>
 *
 * @since 1.0
 */
public final class ProjectMetadataStorage {

    /**
     * Canonical project-internal metadata directory name on every platform.
     */
    public static final String DIRECTORY_NAME = ".synesis";

    /**
     * Portable project metadata file name beneath {@link #DIRECTORY_NAME}.
     */
    public static final String PROJECT_METADATA_FILE = "project.json";

    private static final Logger LOGGER = Logger.getLogger(ProjectMetadataStorage.class.getName());
    private final VisibilityApplier visibilityApplier;

    /**
     * Creates storage using the JDK DOS attribute view when the filesystem
     * provides one.
     */
    public ProjectMetadataStorage() {
        this(ProjectMetadataStorage::applyWindowsVisibility);
    }

    /**
     * Creates storage with a package-local visibility seam for deterministic
     * failure testing.
     *
     * @param visibilityApplier local visibility operation
     */
    ProjectMetadataStorage(VisibilityApplier visibilityApplier) {
        this.visibilityApplier = Objects.requireNonNull(visibilityApplier, "visibilityApplier");
    }

    private static VisibilityResult applyWindowsVisibility(Path metadataRoot) throws IOException {
        DosFileAttributeView view = Files.getFileAttributeView(metadataRoot, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            return new VisibilityResult(false, false, "DOS_HIDDEN_NOT_APPLICABLE");
        }
        var attributes = view.readAttributes();
        if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isDirectory()) {
            throw new IOException("METADATA_ROOT_NOT_SAFE");
        }
        if (attributes.isHidden()) {
            return new VisibilityResult(true, true, "ALREADY_HIDDEN");
        }
        view.setHidden(true);
        return new VisibilityResult(true, true, "HIDDEN_APPLIED");
    }

    private static Path normalize(Path projectRoot) {
        return Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
    }

    private static void validateDirectory(Path directory, String label) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isDirectory()) {
            throw new IOException(label + " is not a safe directory");
        }
    }

    private static void validateMetadataFile(Path metadataFile) throws IOException {
        if (!Files.exists(metadataFile, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        BasicFileAttributes attributes = Files.readAttributes(metadataFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isRegularFile()) {
            throw new IOException("project metadata file is not a safe regular file");
        }
    }

    private static VisibilityResult failure(Throwable failure) {
        LOGGER.log(Level.FINE, "Could not apply project metadata visibility policy: HIDDEN_APPLY_FAILED", failure);
        return new VisibilityResult(false, true, "HIDDEN_APPLY_FAILED");
    }

    private static void createRoot(Path metadataRoot) throws IOException {
        if (Files.exists(metadataRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.createDirectory(metadataRoot);
        } catch (FileAlreadyExistsException race) {
            // The competing creator is validated immediately below.
        }
    }

    /**
     * Returns the canonical metadata root for a project without creating it.
     *
     * @param projectRoot project directory
     * @return normalized {@code .synesis} path
     */
    public Path root(Path projectRoot) {
        return normalize(projectRoot).resolve(DIRECTORY_NAME);
    }

    /**
     * Returns the canonical portable metadata file for a project without
     * creating it.
     *
     * @param projectRoot project directory
     * @return normalized {@code .synesis/project.json} path
     */
    Path projectMetadata(Path projectRoot) {
        return root(projectRoot).resolve(PROJECT_METADATA_FILE);
    }

    /**
     * Creates or validates the metadata root and re-ensures local visibility.
     *
     * <p>Creation/validation failures are real storage failures and are
     * propagated. A visibility-attribute failure is deliberately returned as a
     * diagnostic result so a valid project is not treated as corrupt. The
     * method never follows the metadata-root link and never changes metadata
     * bytes.</p>
     *
     * @param projectRoot project directory
     * @return visibility outcome, including a bounded diagnostic
     * @throws IOException if the project root or metadata root is unsafe or
     *                     cannot be created/read
     */
    public VisibilityResult ensure(Path projectRoot) throws IOException {
        Path normalizedProjectRoot = normalize(projectRoot);
        validateDirectory(normalizedProjectRoot, "project root");
        Path metadataRoot = root(normalizedProjectRoot);
        createRoot(metadataRoot);
        validateDirectory(metadataRoot, "metadata root");
        validateMetadataFile(metadataRoot.resolve(PROJECT_METADATA_FILE));
        try {
            return visibilityApplier.apply(metadataRoot);
        } catch (IOException | UnsupportedOperationException | SecurityException failure) {
            return failure(failure);
        }
    }

    /**
     * Describes the local presentation-policy result without making it part of
     * project identity or authority.
     *
     * @param hidden     whether the directory is known to be hidden
     * @param applicable whether a DOS visibility policy was applicable
     * @param diagnostic bounded result diagnostic
     */
    public record VisibilityResult(boolean hidden, boolean applicable, String diagnostic) {

        /**
         * Validates the diagnostic projection.
         */
        public VisibilityResult {
            Objects.requireNonNull(diagnostic, "diagnostic");
        }
    }

    /**
     * Minimal test seam for the platform-specific presentation operation.
     */
    @FunctionalInterface
    interface VisibilityApplier {

        /**
         * Applies local directory presentation state.
         *
         * @param metadataRoot validated metadata root
         * @return bounded outcome
         * @throws IOException when the platform operation is unavailable or denied
         */
        VisibilityResult apply(Path metadataRoot) throws IOException;
    }
}
