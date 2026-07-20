# Synesis Link onboarding process evidence

- Date: 2026-07-20
- Scope: two local Java processes with separate temporary
  `SYNESIS_LINK_PROFILE` directories; this is not two-machine evidence.
- Host observed: `IDENTITY_CREATED`, `SESSION_CREATED`, `LISTENER_READY`,
  `CANDIDATES_GATHERED=10`, `DESCRIPTOR_CREATED`, `INVITE_CREATED`,
  `SHARE_LINK`, `QR_RENDERED`, `PEER_CONNECTED`, `PEER_IDENTITY_VERIFIED`,
  `CONTROL_READY=true`, `LIVENESS=LIVE`, `SESSION_CLOSED`; exit code 0.
- Join observed: `INVITE_PARSED`, `INVITE_VERIFIED`, `IDENTITY_CREATED`,
  `HOST_IDENTITY_PINNED`, `LOCAL_DESCRIPTOR_CREATED`, `CANDIDATES_GATHERED=10`,
  `PATH_SELECTED`, `PEER_CONNECTED`, `PEER_IDENTITY_VERIFIED`,
  `CONTROL_READY=true`, `LIVENESS=LIVE`, `WORK_RESULT=OK`,
  `SESSION_CLOSED`; exit code 0.
- Security boundary: the invitation capability was included in the signed
  handshake transcript; host identity authentication remained mandatory; the
  capability was consumed at authenticated binding, before control readiness.
- Claim boundary: no physical onboarding, NAT traversal, relay, or packaging
  claim follows from this process evidence.
