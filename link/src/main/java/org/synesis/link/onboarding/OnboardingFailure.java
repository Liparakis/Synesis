package org.synesis.link.onboarding;

import java.util.Objects;
import org.synesis.link.protocol.LinkCompatibility;

/**
 * Typed failure at the Link onboarding boundary.
 *
 * <p>Messages are deliberately generic and must not contain invitations,
 * private keys, or profile paths. The causal exception is retained for local diagnostics but is not
 * part of the stable terminal contract.
 *
 * @since 1.0
 */
public final class OnboardingFailure extends Exception {

  @java.io.Serial
  private static final long serialVersionUID = 1L;
  /**
   * Stable typed classification for this failure.
   */
  private final OnboardingFailureCode code;
  /**
   * Both compatibility numbers, or {@code null} when the failure is not a mismatch.
   */
  private final LinkCompatibility.Mismatch mismatch;

  /**
   * Creates a redacted typed failure.
   *
   * @param code  stable failure classification
   * @param cause underlying failure, or {@code null}
   */
  public OnboardingFailure(OnboardingFailureCode code, Throwable cause) {
    this(code, cause, null);
  }

  /**
   * Creates a failure that may carry the two compatibility numbers that differ. A non-null
   * mismatch always has the code {@link OnboardingFailureCode#INCOMPATIBLE_VERSION}.
   *
   * @param code     stable failure classification
   * @param cause    underlying failure, or {@code null}
   * @param mismatch both numbers, or {@code null}
   */
  public OnboardingFailure(OnboardingFailureCode code, Throwable cause,
      LinkCompatibility.Mismatch mismatch) {
    super(Objects.requireNonNull(code, "code")
        .name(), cause);
    this.code = mismatch == null ? code : OnboardingFailureCode.INCOMPATIBLE_VERSION;
    this.mismatch = mismatch;
  }

  /**
   * Returns both compatibility numbers when the failure is
   * {@link OnboardingFailureCode#INCOMPATIBLE_VERSION}.
   *
   * @return the mismatch, whose message tells the person what to do, or {@code null}
   */
  public LinkCompatibility.Mismatch mismatch() {
    return mismatch;
  }

  /**
   * Returns the stable failure classification.
   *
   * @return failure code
   */
  public OnboardingFailureCode code() {
    return code;
  }
}
