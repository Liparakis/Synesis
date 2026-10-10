package org.synesis.coordination.domain.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Pins the persisted text of a selector, which snapshot claim selectors store. */
class ResourceSelectorTextTest {

  @Test
  void exactPathEncodesAsKindColonValue() {
    assertEquals("PATH_EXACT:src/A.java", ResourceSelector.pathExact("src/A.java").encode());
  }

  @Test
  void subtreeEncodesAsKindColonValue() {
    assertEquals("PATH_SUBTREE:src/app", ResourceSelector.pathSubtree("src/app").encode());
  }

  @Test
  void parseReadsBackWhatEncodeWrote() {
    ResourceSelector exact = ResourceSelector.pathExact("src/A.java");
    ResourceSelector subtree = ResourceSelector.pathSubtree("src/app");
    assertEquals(Optional.of(exact), ResourceSelector.parse(exact.encode()));
    assertEquals(Optional.of(subtree), ResourceSelector.parse(subtree.encode()));
  }

  @Test
  void parseReturnsEmptyForTextThatIsNotASelector() {
    for (String text :
        new String[] {
          "", "PATH_EXACT", ":x", "PATH_EXACT:", "UNKNOWN:x", "PATH_EXACT:../x", "path_exact:x"
        }) {
      assertEquals(Optional.empty(), ResourceSelector.parse(text), text);
    }
  }
}
