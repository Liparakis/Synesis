package org.synesis.cli.command.lifecycle;

import java.nio.file.Path;
import java.util.Optional;

import org.synesis.cli.command.link.LinkFirewallRule;
import org.synesis.cli.daemon.DaemonClient;

import org.synesis.workspace.doctor.NetworkProbe;

/**
 * What {@code synesis doctor} can see of the network from the CLI process: the installation daemon's runtimes and the
 * Windows Firewall rule. Neither question starts anything or changes anything.
 */
final class DoctorNetworkProbe implements NetworkProbe {

    @Override
    public Optional<Boolean> runtimeRunning(Path projectRoot) {
        return Optional.of(DaemonClient.holdsProjectRuntime(projectRoot));
    }

    @Override
    public Optional<Firewall> firewall() {
        return LinkFirewallRule.problem().map(problem -> "MISSING".equals(problem) ? Firewall.MISSING : Firewall.STALE);
    }
}
