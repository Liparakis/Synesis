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

/** Installs and verifies the provider-managed Synesis Manual. */
public final class ProviderManualService {

    /** Creates a provider-manual attestation service. */
    public ProviderManualService() { }

    /** Current managed manual version. */
    public static final int VERSION = 1;
    private static final String MANUAL_DIRECTORY = "synesis-manual";
    private static final String MANUAL_FILE = "SKILL.md";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String CONTENT = "---\nname: synesis-manual\ndescription: Follow Synesis lane coordination, claim, inbox, mutation, recovery, and safe-stopping rules.\n---\n\n# Synesis Manual\n\nUse the durable Synesis coordination state as authoritative. Establish the exact session before mutation, announce intent, acquire only non-overlapping repository-relative claims, and keep every mutation inside the assigned isolated lane.\n\nTreat `get_next_action` as a durable at-least-once inbox. Read it at session start and after blocked or completed actions. Follow its recommended tool and typed arguments; do not guess identifiers, busy-poll, or blindly retry failed mutations.\n\nPublish capability implementations only when the inbox supplies the exact capability request handle. Ordinary lane completion uses `finish_lane`, which validates, publishes, integrates, and closes the lane. Do not invent legacy tool names or call capability publication as a substitute for lane completion.\n\nIf the lane is suspended, cancelled, revoked, or stale, preserve its work and wait for an authorized recovery or handoff. Never edit another lane or the control checkout.\n\nClose or cancel your own lane when finished, and report actionable failures without bypassing Synesis.\n";
    private static final Object INSTALL_LOCK = new Object();

    /** Result of a manual ownership and content attestation.
     * @param valid whether the manual is valid
     * @param version installed manual version
     * @param contentHash actual content hash
     * @param reason attestation reason
     */
    public record Attestation(boolean valid, int version, String contentHash, String reason) {
        /** Validates an attestation result. */
        public Attestation {
            Objects.requireNonNull(contentHash, "contentHash");
            Objects.requireNonNull(reason, "reason");
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
            String hash = hash(CONTENT.getBytes(StandardCharsets.UTF_8));
            Files.writeString(manual, CONTENT, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("provider", provider);
            manifest.put("name", "synesis-manual");
            manifest.put("version", VERSION);
            manifest.put("contentHash", hash);
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
                return new Attestation(false, 0, "", "MANUAL_MISSING");
            }
            Object parsed = ProviderJson.parse(Files.readString(manifestPath));
            if (!(parsed instanceof Map<?, ?> raw)) return new Attestation(false, 0, "", "MANIFEST_INVALID");
            int version = raw.get("version") instanceof Number n ? n.intValue() : 0;
            String expected = String.valueOf(raw.get("contentHash"));
            String actual = hash(Files.readAllBytes(manual));
            String canonical = hash(CONTENT.getBytes(StandardCharsets.UTF_8));
            boolean valid = provider.equals(String.valueOf(raw.get("provider")))
                    && "synesis-manual".equals(String.valueOf(raw.get("name")))
                    && version == VERSION && expected.equals(canonical) && actual.equals(canonical);
            return new Attestation(valid, version, actual, valid ? "ATTESTED" : "MANUAL_MODIFIED_OR_OUTDATED");
        } catch (Exception failure) {
            return new Attestation(false, 0, "", "MANUAL_UNVERIFIABLE");
        }
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
