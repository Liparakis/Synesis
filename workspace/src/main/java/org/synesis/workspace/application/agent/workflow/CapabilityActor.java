package org.synesis.workspace.application.agent.workflow;

/**
 * Identifies the caller in capability-request matching. Shared by the completion, capability
 * and orchestration stages, which all match requests against the same identity.
 *
 * @param nodeId       node identity of the calling process
 * @param supervisorId supervisor recorded in the provider binding
 * @param workerId     worker recorded in the provider binding
 * @param sessionId    provider session that owns validation context
 */
record CapabilityActor(String nodeId, String supervisorId, String workerId, String sessionId) {

}
