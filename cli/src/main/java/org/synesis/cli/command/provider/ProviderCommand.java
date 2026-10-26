package org.synesis.cli.command.provider;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ErrorShape;
import org.synesis.workspace.application.provider.ProviderApplicationService.ProviderResult;
import picocli.CommandLine.Command;

/**
 * Parent command for project-local provider lifecycle operations. It also owns how a provider result is reported, so
 * install, status and uninstall refuse in the same shape.
 */
@Command(name = "provider", description = "Manage project-local provider integrations.", mixinStandardHelpOptions = true)
public final class ProviderCommand {

    private static final List<String> STATE_KEYS = List.of("PROVIDER_STATUS", "PROVIDER_INSTALL_RESULT", "PROVIDER_UNINSTALL_RESULT");

    /**
     * Creates the parent command.
     */
    public ProviderCommand() {
    }

    /**
     * Prints a provider result as the service built it, then, when it failed, {@code MESSAGE} and {@code FIX} beside its
     * own {@code ERROR}. A failing result without an {@code ERROR} of its own (a broken or degraded status) gets one named
     * after its state.
     *
     * @param runtime composed runtime
     * @param result  what the provider service returned
     * @return the result's exit code
     */
    static int report(CliRuntime runtime, ProviderResult result) {
        var values = result.values();
        values.forEach((key, value) -> runtime.terminal().stdout(key + "=" + value));
        boolean hasError = values.containsKey("ERROR");
        if (hasError || result.exitCode() != 0) {
            String provider = values.getOrDefault("PROVIDER", "<provider>");
            Consumer<String> out = line -> runtime.terminal().stdout(line.replace("<provider>", provider));
            if (hasError) {
                ErrorShape.explain(out, values.get("ERROR"));
            } else {
                String state = STATE_KEYS.stream().map(values::get).filter(Objects::nonNull).findFirst().orElse("FAILED");
                ErrorShape.print(out, "PROVIDER_" + state);
            }
        }
        return result.exitCode();
    }
}
