package org.synesis.workspace.provider.antigravity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.synesis.workspace.integration.antigravity.AntigravityHookAdapter;
import org.synesis.workspace.provider.ProviderIntegration;
import org.synesis.workspace.provider.ProviderJson;
import org.synesis.workspace.provider.ProviderSupportLevel;

/** Antigravity provider configuration and synthetic hook contract. */
public final class AntigravityProviderIntegration implements ProviderIntegration {
    private static final String WRAPPER_NAME = "run-antigravity-hook.ps1";
    private static final String WINDOWS_WRAPPER = """
            $ErrorActionPreference = 'Stop'
            $projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
            Set-Location -LiteralPath $projectRoot
            $profile = Join-Path $PSScriptRoot 'profile'
            $launcher = Get-Command synesis.cmd -CommandType Application -ErrorAction SilentlyContinue |
                Select-Object -First 1 -ExpandProperty Source
            if (-not $launcher) {
              $launcher = Join-Path $env:LOCALAPPDATA 'Synesis\\bin\\synesis.cmd'
            }
            [Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false)
            [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
            $payload = [Console]::In.ReadToEnd()
            try {
              $info = New-Object System.Diagnostics.ProcessStartInfo
              $info.FileName = $env:ComSpec
              $info.Arguments = '/d /s /c ""' + $launcher + '" hook antigravity --profile "' + $profile + '""'
              $info.WorkingDirectory = $projectRoot
              $info.UseShellExecute = $false
              $info.RedirectStandardInput = $true
              $info.RedirectStandardOutput = $true
              $info.RedirectStandardError = $true
              $process = New-Object System.Diagnostics.Process
              $process.StartInfo = $info
              $process.Start() | Out-Null
              $process.StandardInput.Write($payload)
              $process.StandardInput.Close()
              $stdout = $process.StandardOutput.ReadToEnd()
              $stderr = $process.StandardError.ReadToEnd()
              $process.WaitForExit()
              $response = $stdout.Trim() | ConvertFrom-Json -ErrorAction Stop
              if ([string]$response.decision -notin @('allow', 'ask', 'force_ask', 'deny')) {
                throw 'Invalid Synesis hook decision.'
              }
              [Console]::Out.WriteLine(($response | ConvertTo-Json -Compress -Depth 10))
              if ($stderr) {
                $diagnostic = $stderr.Trim()
                if ($diagnostic.Length -gt 4096) { $diagnostic = $diagnostic.Substring(0, 4096) }
                [Console]::Error.WriteLine($diagnostic)
              }
            } catch {
              $diagnostic = [string]$_.Exception.Message
              if ($diagnostic.Length -gt 4096) { $diagnostic = $diagnostic.Substring(0, 4096) }
              [Console]::Error.WriteLine(('SYNESIS_HOOK_DIAGNOSTIC=' + $diagnostic))
              [Console]::Out.WriteLine('{"decision":"deny","reason":"Synesis hook unavailable"}')
            }
            exit 0
            """;

    /** Creates the Antigravity integration. */
    public AntigravityProviderIntegration() {
    }
    @Override public String id() { return "antigravity"; }
    @Override public ProviderSupportLevel supportLevel() { return ProviderSupportLevel.BETA; }
    @Override public Path configurationPath(Path projectRoot) { return projectRoot.resolve(".agents/hooks.json"); }
    @Override public String hookGroup() { return "synesis-guardrail"; }
    @Override public String managedHookId() { return "synesis-antigravity"; }
    @Override public String matcher() { return "write_to_file|replace_file_content|multi_replace_file_content"; }

    /**
     * Requires a real Antigravity run before reporting a healthy provider.
     *
     * @return {@code true}; synthetic checks do not prove Antigravity loaded the hook
     */
    @Override public boolean requiresRealValidation() { return true; }

    /**
     * Reports the missing real-run validation state.
     *
     * @return unvalidated trust state
     */
    @Override public String trustStatus() { return "UNVALIDATED"; }

    /**
     * Builds the Windows PowerShell wrapper command used by Antigravity.
     *
     * @param launcher generated Synesis launcher
     * @param profile local profile
     * @return provider hook command
     */
    @Override public String hookCommand(Path launcher, Path profile) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return ProviderIntegration.super.hookCommand(launcher, profile);
        }
        return "powershell.exe -NoProfile -ExecutionPolicy Bypass -File " + wrapperPath(profile);
    }

    /**
     * Writes the project-local Windows wrapper used by Antigravity.
     *
     * @param profile local profile directory
     * @throws IOException if the wrapper cannot be written
     */
    public void writeWrapper(Path profile) throws IOException {
        Path wrapper = wrapperPath(profile);
        Files.createDirectories(wrapper.getParent());
        Files.writeString(wrapper, WINDOWS_WRAPPER, StandardCharsets.UTF_8);
    }

    /**
     * Returns the project-local wrapper path.
     *
     * @param profile local profile directory
     * @return wrapper path
     */
    public Path wrapperPath(Path profile) {
        return profile.toAbsolutePath().normalize().getParent().resolve(WRAPPER_NAME);
    }

    @Override
    public SyntheticCheck syntheticCheck(Path profile, Path projectRoot) {
        AntigravityHookAdapter adapter = new AntigravityHookAdapter(profile);
        String root = jsonPath(projectRoot);
        String protectedEvent = "{\"workspacePaths\":[\"" + root + "\"],\"toolCall\":{\"name\":\"write_to_file\",\"args\":{\"TargetFile\":\"" + jsonPath(projectRoot.resolve("src/protected.txt")) + "\"}}}";
        String allowedEvent = protectedEvent.replace("src/protected.txt", "src/free.txt");
        var blocked = adapter.processJson(protectedEvent);
        var allowed = adapter.processJson(allowedEvent);
        return new SyntheticCheck(blocked.outcome() == AntigravityHookAdapter.Outcome.BLOCKED,
                allowed.outcome() == AntigravityHookAdapter.Outcome.ALLOWED,
                valid(blocked.responseJson()) && valid(allowed.responseJson()), blocked.responseJson(), allowed.responseJson());
    }

    private static boolean valid(String json) { try { return ProviderJson.parse(json) instanceof java.util.Map<?, ?>; } catch (RuntimeException failure) { return false; } }
    private static String jsonPath(Path path) { return path.toAbsolutePath().normalize().toString().replace('\\', '/'); }
}
