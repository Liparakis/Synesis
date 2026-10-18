package org.synesis.link.protocol;

/**
 * The compatibility number: the one value that decides whether two Synesis builds may talk.
 *
 * <p><b>The rule:</b> raise {@link #CURRENT} whenever any format sent between computers changes,
 * that is the invitation, the answer, the handshake, the application frames, and later the
 * replicated log. Never raise it for anything else, and never derive it from the build string:
 * two builds with different version strings and the same number work together.
 *
 * <p>The number sits where a future build can still read it, whatever else changed: in the first
 * two payload bytes of an invitation and an answer, and in the two bytes after the magic and
 * format byte of the handshake envelope. A build that finds a different number refuses to go on,
 * with an {@link IncompatibleVersionException} before it parses anything else.
 *
 * <p>It stands at 7: code moves between the computers of a project over Link ({@code SCX1},
 * ADR-0098 step 3, "Code over Link"), which the build at 6 neither serves nor asks for. It was 6
 * for the project invitation ({@code SLP1}, ADR-0098 "Joining, revised") and a joiner held unserved
 * until the owner accepts it, and 5 for the member protocol of the replicated project log and the
 * 512 KiB application-stream payload bound.
 *
 * <p>It started at 4 because the invitation and answer layouts before this rule, which carried no
 * number, were the third generation ({@code SLB3}, {@code SLA3}). Builds from before the rule
 * cannot read the number, so they report a newer link as invalid; that cannot be fixed.
 *
 * @since 1.0
 */
public final class LinkCompatibility {

  /** The compatibility number of this build. */
  public static final int CURRENT = 7;

  private LinkCompatibility() {
  }

  /**
   * Two compatibility numbers that differ, and what carried the other one.
   *
   * @param subject {@code invitation}, {@code answer} or {@code connection}
   * @param local   this build's number
   * @param remote  the other side's number
   */
  public record Mismatch(String subject, int local, int remote) implements java.io.Serializable {

    /**
     * Returns the plain sentence for the person: both numbers, who is older, what to do.
     *
     * @return a message safe to show as is
     */
    public String message() {
      String origin = subject.equals("connection")
          ? "The other computer runs a different Synesis version"
          : "This " + subject + " comes from a different Synesis version";
      String numbers = " (compatibility " + remote + "; this computer has " + local + "). ";
      String then = subject.equals("invitation") ? "ask for a new invitation."
          : subject.equals("answer") ? "create a new invitation." : "try again.";
      return origin + numbers + (remote > local
          ? "This computer is older: update Synesis on this computer, then " + then
          : "The other computer is older: ask its owner to update Synesis, then " + then);
    }
  }
}
