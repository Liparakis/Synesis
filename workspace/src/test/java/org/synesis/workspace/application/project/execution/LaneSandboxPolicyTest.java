package org.synesis.workspace.application.project.execution;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.project.execution.sandbox.SandboxPolicy;

/**
 * What a lane's command cannot see, whatever the platform: the keys of the project and the application state.
 */
class LaneSandboxPolicyTest {

    @TempDir
    Path temp;

    private String previousHome;

    @BeforeEach
    void isolateHome() throws Exception {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(temp.resolve("home")).toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", previousHome);
    }

    private static boolean hides(SandboxPolicy policy, Path path) {
        Path real = ApplicationStatePaths.real(path);
        return policy.hidden().stream().anyMatch(real::startsWith);
    }

    @Test
    void theOperatorKeyTheNodeKeyAndTheApplicationStateAreHidden() throws Exception {
        Path control = Files.createDirectories(temp.resolve("project"));
        Path synesis = Files.createDirectories(control.resolve(".synesis"));
        ProjectLocation location = new ProjectLocation(control, synesis, synesis.resolve("project.json"), synesis.resolve("local/profile"), UUID.randomUUID(), Instant.now());
        Files.createDirectories(location.operatorKeyFile().getParent());
        Files.writeString(location.operatorKeyFile(), "key");
        Files.createDirectories(location.linkDirectory());
        Files.writeString(location.linkDirectory().resolve("identity.bin"), "key");
        Path state = Files.createDirectories(temp.resolve("home/.synesis/Synesis"));
        Path lane = Files.createDirectories(state.resolve("workspaces/p/worktrees/lane1"));
        Path privateTemp = Files.createDirectories(temp.resolve("private"));

        SandboxPolicy policy = LaneSandboxPolicy.derive(lane, control, privateTemp);

        assertTrue(hides(policy, location.operatorKeyFile()), "the operator key is hidden: " + policy.hidden());
        assertTrue(hides(policy, location.linkDirectory().resolve("identity.bin")), "the node key is hidden: " + policy.hidden());
        assertTrue(hides(policy, state.resolve("link/identity.bin")), "the application state is hidden: " + policy.hidden());
        assertTrue(hides(policy, state.resolve("workspaces/p/worktrees/lane2/f.txt")), "other lanes are hidden: " + policy.hidden());
    }
}
