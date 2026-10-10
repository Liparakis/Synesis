package org.synesis.workspace.lifecycle.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A digest over a durable object must not depend on the iteration order of its maps, because
 * {@code Map.of} iterates in a different order in every JVM run.
 */
class CommandCanonicalFormTest {

  private static Map<String, Object> ordered(String... keysAndValues) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int index = 0; index < keysAndValues.length; index += 2) {
      map.put(keysAndValues[index], keysAndValues[index + 1]);
    }
    return map;
  }

  @Test
  void requestKeyAndDigestsArePinnedToTheirKeySortedJson() {
    String requestDigest =
        ProjectCommandCanonicalizer.requestDigest(List.of("echo", "ok"), ".", 10);

    assertEquals(
        "01ae1f44fe93719f8af8eb3974f3453e4e741aa84d0dbb60c4e23b620c61a9a4",
        ProjectCommandCanonicalizer.requestKey("anchor-1", "n:1"));
    assertEquals("4269fe56fe779cc6dcb319ea1d75900c301429d82488ba68c5b06f37ed88da7d", requestDigest);
    assertEquals(
        "91d85db15b502f066a3ec47cf3a2200ae4c265d4ccd66e9370c9630c28a740d3",
        ProjectCommandCanonicalizer.requestDigest(List.of("echo", "ok"), null, null));
    assertEquals(
        "5d3f9edaae149dc9ccc2c2370d6b401d28dce7a38673df3d9da57d6d15c3bb76",
        ProjectCommandCanonicalizer.semanticDigest(requestDigest, "codex", "connection", "scope-1"));
  }

  @Test
  void digestIgnoresTopLevelKeyOrder() {
    Map<String, Object> first = ordered("anchorId", "anchor-1", "requestId", "n:1");
    Map<String, Object> second = ordered("requestId", "n:1", "anchorId", "anchor-1");

    assertEquals(
        CommandDurableFormat.canonicalDigest(first), CommandDurableFormat.canonicalDigest(second));
  }

  @Test
  void digestIgnoresNestedKeyOrder() {
    Map<String, Object> first = ordered("response", "x");
    first.put("nested", ordered("a", "1", "b", "2"));
    Map<String, Object> second = ordered("response", "x");
    second.put("nested", ordered("b", "2", "a", "1"));

    assertEquals(
        CommandDurableFormat.canonicalDigest(first), CommandDurableFormat.canonicalDigest(second));
  }

  @Test
  void integrityVerifiesWhateverOrderTheFileHoldsNestedKeysIn() throws Exception {
    Map<String, Object> source = ordered("anchorId", "anchor-1");
    source.put("processIdentity", ordered("pid", "1", "commandLine", "java"));
    Map<String, Object> durable = CommandDurableFormat.withIntegrity(source);
    Map<String, Object> reordered = new LinkedHashMap<>(durable);
    reordered.put("processIdentity", ordered("commandLine", "java", "pid", "1"));

    CommandDurableFormat.verify(reordered);
  }
}
