# SL-D-040 Checkpoint 3A Docker lab

This directory contains the disposable process-isolation harness for the local
SL-D-040 Checkpoint 3A slice. It runs six independent JVMs in a private Docker
network and uses the existing Link/overlay classes from the source checkout.

The harness is deliberately a lab adapter, not a new product authority. It
creates one explicitly signed six-member membership snapshot, bootstraps the
twelve target edges through the normal compact SLB2 host/join path, then feeds
the signed candidate descriptors produced by each node into the existing
bounded reconciler. After a container or network endpoint changes, the harness
refreshes those signed descriptors explicitly. That step is evidence of a
controlled lab seam, not automatic endpoint discovery in the shipped CLI.
The partition step uses reversible `tc netem loss 100%` isolation so the live
JVM keeps its endpoint while traffic is cut. Recovery then recreates the six
processes one at a time with the same persisted identities, refreshing signed
descriptors after each join; this is the explicit stale-session recovery
descriptors after each join. The process reset is graceful before the
container is replaced, which is the explicit stale-session recovery boundary.
Hard-kill plus same-identity container recreation remains the changed-endpoint
test. A raw Docker network detach/reattach of a live QUIC JVM is intentionally
not treated as equivalent: the current process does not reliably rebind its
active socket after namespace reattachment.

Run from the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/p2p-lab/run.ps1 -Cycles 1
```

The script builds a Linux x86_64 distribution, builds a disposable Linux image,
creates a private Docker network, records evidence under a fresh run directory,
and removes the containers/network unless `-Keep` is supplied. It does not prune
Docker data or remove the image. KVM/native-Linux and physical NAT/Internet
claims are outside this lab.
