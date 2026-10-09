package org.synesis.workspace.application.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.provider.ProviderIntegration;

import static org.synesis.workspace.application.provider.ProviderConfigurationFiles.atomicWrite;

/**
 * Installs and removes the instruction file a provider reads from the project root. Only a regular
 * file that opens with the ownership marker is Synesis's to replace or remove; any other file at
 * that path belongs to the user and is left untouched. It is separate from
 * {@link ProviderApplicationService} so the ownership test is the same for install and uninstall.
 */
final class ProviderProjectInstructions {

    private ProviderProjectInstructions() {
    }

    /**
     * Installs the provider's project instruction file, if it has one.
     *
     * @param location project location
     * @param provider provider integration
     * @return {@code NOT_APPLICABLE}, {@code INSTALLED}, {@code UNCHANGED}, or {@code CONFLICT}
     *         when a file the user owns already occupies the path and was left alone
     * @throws IOException when the file cannot be read or written
     */
    static String ensureProjectInstructions(ProjectLocation location, ProviderIntegration provider) throws IOException {
        ProviderIntegration.ProjectInstructions instructions = provider.projectInstructions(location.root()).orElse(null);
        if (instructions == null) {
            return "NOT_APPLICABLE";
        }
        Path path = instructions.path();
        if (Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (!ownedInstructions(path)) {
                return "CONFLICT";
            }
            if (instructions.content().equals(Files.readString(path, StandardCharsets.UTF_8))) {
                return "UNCHANGED";
            }
        }
        atomicWrite(path, instructions.content());
        return "INSTALLED";
    }

    static void removeProjectInstructions(ProjectLocation location, ProviderIntegration provider) throws IOException {
        ProviderIntegration.ProjectInstructions instructions = provider.projectInstructions(location.root()).orElse(null);
        if (instructions != null && ownedInstructions(instructions.path())) {
            Files.delete(instructions.path());
        }
    }

    /** Only a regular file that opens with the ownership marker is Synesis's to replace or remove. */
    private static boolean ownedInstructions(Path path) throws IOException {
        return Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && Files.readString(path, StandardCharsets.UTF_8).startsWith(ProviderIntegration.ProjectInstructions.OWNERSHIP_MARKER);
    }
}
