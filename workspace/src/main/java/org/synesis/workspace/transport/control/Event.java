package org.synesis.workspace.transport.control;

import org.synesis.link.onboarding.OnboardingEventType;

/**
 * Immutable safe event delivered to the control plane.
 *
 * @param sequence local event sequence
 * @param type     event type
 * @param value    redacted bounded value
 */
record Event(long sequence, OnboardingEventType type, String value) {

}