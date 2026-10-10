package org.synesis.workspace.application.provider;

import static org.synesis.workspace.application.provider.ProviderConfigurationFiles.METADATA_SCHEMA;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The install record kept in {@code .synesis/local/providers/<id>.json}. It owns the file's key
 * names and their order, so the writer and the status reader cannot drift apart. The schema version
 * is not a component: {@link #toMap()} always writes the current one, and the reader checks the
 * stored value itself because an obsolete record is reported rather than interpreted.
 *
 * <p>{@link #fromMap(Map)} is as lenient as the hand-written reads it replaces: a missing or non-text
 * value reads as the text {@code "null"} and never fails.</p>
 */
record ProviderMetadata(String provider, String supportLevel, String installedAt, String configurationPath, String launcherPath, String mcpLauncherPath, String profilePath, String managedEntryId,
        String mcpConfigStatus, String mcpHealth, int manualVersion, String manualContentHash, String wireCompatibilityDigest, String catalogContentDigest, String guidanceArtifactDigest,
        String manualPath, String lastSyntheticCheck) {

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", METADATA_SCHEMA);
        map.put("provider", provider);
        map.put("supportLevel", supportLevel);
        map.put("installedAt", installedAt);
        map.put("configurationPath", configurationPath);
        map.put("launcherPath", launcherPath);
        map.put("mcpLauncherPath", mcpLauncherPath);
        map.put("profilePath", profilePath);
        map.put("managedEntryId", managedEntryId);
        map.put("mcpConfigStatus", mcpConfigStatus);
        map.put("mcpHealth", mcpHealth);
        map.put("manualVersion", manualVersion);
        map.put("manualContentHash", manualContentHash);
        map.put("wireCompatibilityDigest", wireCompatibilityDigest);
        map.put("catalogContentDigest", catalogContentDigest);
        map.put("guidanceArtifactDigest", guidanceArtifactDigest);
        map.put("manualPath", manualPath);
        map.put("lastSyntheticCheck", lastSyntheticCheck);
        return map;
    }

    static ProviderMetadata fromMap(Map<String, Object> map) {
        return new ProviderMetadata(text(map, "provider"), text(map, "supportLevel"), text(map, "installedAt"), text(map, "configurationPath"), text(map, "launcherPath"), text(map, "mcpLauncherPath"),
                text(map, "profilePath"), text(map, "managedEntryId"), text(map, "mcpConfigStatus"), text(map, "mcpHealth"), map.get("manualVersion") instanceof Number number ? number.intValue() : 0,
                text(map, "manualContentHash"), text(map, "wireCompatibilityDigest"), text(map, "catalogContentDigest"), text(map, "guidanceArtifactDigest"), text(map, "manualPath"),
                text(map, "lastSyntheticCheck"));
    }

    private static String text(Map<String, Object> map, String key) {
        return String.valueOf(map.get(key));
    }
}
