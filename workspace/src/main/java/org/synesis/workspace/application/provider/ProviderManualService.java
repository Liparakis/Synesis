package org.synesis.workspace.application.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.mcp.contract.McpToolCatalog;

/** Installs and verifies the provider-managed Synesis Manual. */
public final class ProviderManualService {

    /** Creates a provider-manual attestation service. */
    public ProviderManualService() { }

    /** Current managed manual version. */
    public static final int VERSION = 2;
    private static final String MANUAL_DIRECTORY = "synesis-manual";
    private static final String MANUAL_FILE = "SKILL.md";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String CONTENT_PREFIX = "---\nname: synesis-manual\ndescription: Follow Synesis lane coordination, claim, inbox, mutation, recovery, and safe-stopping rules.\n---\n\n# Synesis Manual\n\nUse the durable Synesis coordination state as authoritative. Establish the exact session before mutation, announce intent, acquire only non-overlapping repository-relative claims, and keep every mutation inside the assigned isolated lane.\n\nTreat `get_next_action` as a durable at-least-once inbox. Read it at session start and after blocked or completed actions. A concrete `recommendedTool` with typed `arguments` is the only permission to perform that lifecycle action; do not guess identifiers, busy-poll, or blindly retry failed mutations.\n\nPublish capability implementations only when the inbox supplies the exact capability request handle. A lane that appears complete is not permission to call `finish_lane`, `cancel_lane`, `respond_coordination`, validation, or another lifecycle tool. Execute `finish_lane` only when `get_next_action` projects it with its exact arguments. Do not invent legacy tool names or call capability publication as a substitute for a projected lane action.\n\nIf `get_next_action` reports workflow `IMPLEMENT` without a concrete `recommendedTool` and typed `arguments`, continue and verify the assigned visible coding work, then return to `get_next_action`; do not invent a lifecycle transition merely because the coding appears complete. Do not inspect `.synesis/**` internal metadata through workspace file tools; those paths are protected.\n\nIf the lane is suspended, cancelled, revoked, or stale, preserve its work and wait for an authorized recovery or handoff. Never edit another lane or the control checkout. If work appears complete, return to `get_next_action` and follow its projected close, finish, or cancel action rather than closing the lane directly.\n\nReport actionable failures without bypassing Synesis.\n\n";
    private static final String IMPLEMENT_GUIDANCE = "When `get_next_action` reports workflow `IMPLEMENT` without a concrete `recommendedTool` and typed `arguments`, continue the assigned coding task normally in the visible assigned worktree using the permitted repository operations. Do not inspect `.synesis/**` internal metadata through workspace file tools; those paths are protected. Do not call `finish_lane` or another lifecycle tool merely because the coding appears complete. Return to `get_next_action` after coding progress, a blocked result, or when collaboration is required. When a concrete `recommendedTool` and `arguments` are projected, execute that exact tool with those exact arguments before choosing another Synesis lifecycle action.\n\n";
    private static final Object INSTALL_LOCK = new Object();

    /** Result of a manual ownership and content attestation.
     * @param valid whether the manual is valid
     * @param version installed manual version
     * @param contentHash actual content hash
     * @param reason attestation reason
     * @param provider provider identifier
     * @param wireCompatibilityDigest verified wire compatibility digest
     * @param catalogContentDigest verified catalog content digest
     * @param guidanceArtifactDigest verified rendered guidance artifact digest
     */
    public record Attestation(boolean valid, int version, String contentHash, String reason, String provider,
                              String wireCompatibilityDigest, String catalogContentDigest,
                              String guidanceArtifactDigest) {
        /** Validates an attestation result. */
        public Attestation {
            Objects.requireNonNull(contentHash, "contentHash");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(wireCompatibilityDigest, "wireCompatibilityDigest");
            Objects.requireNonNull(catalogContentDigest, "catalogContentDigest");
            Objects.requireNonNull(guidanceArtifactDigest, "guidanceArtifactDigest");
        }
    }

    /** Resolves the provider-managed skill directory.
     * @param provider provider identifier
     * @return global skill directory
     */
    public Path skillDirectory(String provider) {
        Objects.requireNonNull(provider, "provider");
        Path home = Path.of(System.getProperty("user.home"));
        return switch (provider) {
            case "codex" -> home.resolve(".codex/skills").resolve(MANUAL_DIRECTORY);
            case "claude" -> home.resolve(".claude/skills").resolve(MANUAL_DIRECTORY);
            case "antigravity" -> home.resolve(".gemini/config/skills").resolve(MANUAL_DIRECTORY);
            default -> throw new IllegalArgumentException("unknown provider: " + provider);
        };
    }

    /** Installs the managed manual atomically.
     * @param provider provider identifier
     * @return resulting attestation
     * @throws IOException installation failure
     */
    public Attestation install(String provider) throws IOException {
        synchronized (INSTALL_LOCK) {
            return installLocked(provider);
        }
    }

    private Attestation installLocked(String provider) throws IOException {
        Path directory = skillDirectory(provider);
        Files.createDirectories(directory.getParent());
        Path staging = directory.resolveSibling(MANUAL_DIRECTORY + ".staging-" + Long.toUnsignedString(System.nanoTime()));
        Files.createDirectories(staging);
        try {
            Path manual = staging.resolve(MANUAL_FILE);
            String content = content(provider);
            String hash = hash(content.getBytes(StandardCharsets.UTF_8));
            Files.writeString(manual, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("provider", provider);
            manifest.put("name", "synesis-manual");
            manifest.put("version", VERSION);
            manifest.put("contentHash", hash);
            manifest.put("wireCompatibilityDigest", McpToolCatalog.wireCompatibilityDigest());
            manifest.put("catalogContentDigest", McpToolCatalog.catalogContentDigest());
            manifest.put("guidanceArtifactDigest", McpToolCatalog.guidanceArtifactDigest(
                    "synesis-manual", provider, content.getBytes(StandardCharsets.UTF_8)));
            Files.writeString(staging.resolve(MANIFEST_FILE), ProviderJson.write(manifest) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            if (Files.exists(directory) && attest(provider).valid()) {
                return attest(provider);
            }
            if (Files.exists(directory)) {
                Path backup = directory.resolveSibling(MANUAL_DIRECTORY + ".previous");
                Files.move(directory, backup, StandardCopyOption.REPLACE_EXISTING);
                Files.move(staging, directory, StandardCopyOption.ATOMIC_MOVE);
                deleteTree(backup);
            } else {
                Files.move(staging, directory, StandardCopyOption.ATOMIC_MOVE);
            }
            return attest(provider);
        } finally {
            if (Files.exists(staging)) deleteTree(staging);
        }
    }

    /** Attests the managed manual.
     * @param provider provider identifier
     * @return attestation result
     */
    public Attestation attest(String provider) {
        try {
            Path directory = skillDirectory(provider);
            Path manual = directory.resolve(MANUAL_FILE);
            Path manifestPath = directory.resolve(MANIFEST_FILE);
            if (!Files.isRegularFile(manual) || !Files.isRegularFile(manifestPath)) {
                return invalid("MANUAL_MISSING", provider);
            }
            Object parsed = ProviderJson.parse(Files.readString(manifestPath));
            if (!(parsed instanceof Map<?, ?> raw)) return invalid("MANIFEST_INVALID", provider);
            int version = raw.get("version") instanceof Number n ? n.intValue() : 0;
            String expected = String.valueOf(raw.get("contentHash"));
            String actual = hash(Files.readAllBytes(manual));
            String canonicalContent = content(provider);
            String canonical = hash(canonicalContent.getBytes(StandardCharsets.UTF_8));
            String expectedArtifact = String.valueOf(raw.get("guidanceArtifactDigest"));
            String actualArtifact = McpToolCatalog.guidanceArtifactDigest("synesis-manual", provider,
                    Files.readAllBytes(manual));
            boolean valid = provider.equals(String.valueOf(raw.get("provider")))
                    && "synesis-manual".equals(String.valueOf(raw.get("name")))
                    && version == VERSION && expected.equals(canonical) && actual.equals(canonical)
                    && McpToolCatalog.wireCompatibilityDigest().equals(String.valueOf(raw.get("wireCompatibilityDigest")))
                    && McpToolCatalog.catalogContentDigest().equals(String.valueOf(raw.get("catalogContentDigest")))
                    && expectedArtifact.equals(actualArtifact);
            return new Attestation(valid, version, actual, valid ? "ATTESTED" : "MANUAL_MODIFIED_OR_OUTDATED", provider,
                    String.valueOf(raw.get("wireCompatibilityDigest")),
                    String.valueOf(raw.get("catalogContentDigest")),
                    valid ? actualArtifact : String.valueOf(raw.get("guidanceArtifactDigest")));
        } catch (Exception failure) {
            return invalid("MANUAL_UNVERIFIABLE", provider);
        }
    }

    private static Attestation invalid(String reason, String provider) {
        return new Attestation(false, 0, "", reason, provider, "", "", "");
    }

    private static String content(String provider) {
        return CONTENT_PREFIX + IMPLEMENT_GUIDANCE
                + "MCP wire compatibility digest: `" + McpToolCatalog.wireCompatibilityDigest() + "`\n"
                + "MCP catalog content digest: `" + McpToolCatalog.catalogContentDigest() + "`\n"
                + "Provider guidance renderer: `" + provider + "`\n";
    }

    /** Requires a valid manual for authority-increasing operations.
     * @param provider provider identifier
     * @throws IOException when attestation is invalid
     */
    public void requireAttested(String provider) throws IOException {
        Attestation result = attest(provider);
        if (!result.valid()) throw new IOException("MANUAL_ATTESTATION_REQUIRED:" + result.reason());
    }

    private static String hash(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IOException("manual hash unavailable", failure);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
