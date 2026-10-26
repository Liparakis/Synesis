package org.synesis.workspace.application.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import org.synesis.workspace.provider.ProviderIntegration;

/**
 * Removes the instruction file an earlier Synesis wrote into the project for a provider. Synesis writes
 * no instruction file any more (the installed manual is the one place for the agent instructions), so
 * only the removal is left. Only a regular file that opens with the ownership marker is Synesis's to
 * remove; any other file at that path belongs to the user and is left untouched. It is separate from
 * {@link ProviderApplicationService} so install, uninstall and project initialization share one test.
 */
final class ProviderProjectInstructions {

    private ProviderProjectInstructions() {
    }

    /**
     * Removes the provider's retired project instruction file when it is Synesis's own.
     *
     * @param projectRoot project root
     * @param provider    provider integration
     * @return {@code NOT_APPLICABLE} when the harness never had one, {@code ABSENT} when none is there,
     *         {@code REMOVED}, or {@code CONFLICT} when a file the user owns occupies the path and was
     *         left alone
     * @throws IOException when the file cannot be read or deleted
     */
    static String retire(Path projectRoot, ProviderIntegration provider) throws IOException {
        Path path = provider.retiredProjectInstructions(projectRoot).orElse(null);
        if (path == null) {
            return "NOT_APPLICABLE";
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return "ABSENT";
        }
        if (!ownedInstructions(path)) {
            return "CONFLICT";
        }
        Files.delete(path);
        return "REMOVED";
    }

    /** Only a regular file that opens with the ownership marker is Synesis's to remove. */
    private static boolean ownedInstructions(Path path) throws IOException {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && Files.readString(path, StandardCharsets.UTF_8).startsWith(ProviderIntegration.RETIRED_INSTRUCTIONS_MARKER);
    }
}
