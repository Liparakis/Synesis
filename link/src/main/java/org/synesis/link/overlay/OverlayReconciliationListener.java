package org.synesis.link.overlay;

/**
 * Receives bounded overlay reconciliation diagnostics.
 *
 * <p>Callbacks must be short and non-blocking. A throwing listener is isolated
 * from reconciliation state.
 *
 * @since 1.0
 */
@FunctionalInterface
public interface OverlayReconciliationListener {

  /**
   * Observes one safe reconciliation event.
   *
   * @param event immutable event
   */
  void onEvent(OverlayReconciliationEvent event);
}
