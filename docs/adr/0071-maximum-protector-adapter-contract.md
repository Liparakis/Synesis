# ADR-0071: External maximum-protector adapter and signed candidate gate

- Status: Accepted for `SYN-009E`; execution remains blocked without licensed
  tooling and release signing authority
- Date: 2026-09-09
- Scope: maximum-release Gradle boundary and existing bootstrap manifest signer

## Context

ADR-0070 separates the readable developer build, the open-source
`protection-lite` baseline, and the commercial `maximum-release` profile. The
repository has no DashO, Zelix, Virbox, or equivalent commercial protector
installed.
Leaving only an unconditional placeholder task makes the intended release
boundary underspecified, but embedding a fake protector or inventing a
home-grown virtual machine would make the security claim worse.

The existing bootstrap signer and detached manifest trust root are the
authoritative distribution boundary. A maximum candidate must be produced by a
real licensed protector, retain private recovery material, and be signed only
after the protected archive is final. Its six transformation results are the
mandatory commercial Rings 1–6 from the current brief; they are not optional
vendor-capability decorations.

## Decision

Add one explicit provider-agnostic adapter contract to the release-only Gradle
pipeline. The adapter is an external executable owned by the release
environment and receives a versioned request file. It must return a protected
CLI or standalone relay bundle, private evidence, exact protector
identity/version, all six mandatory Ring 1–6 statuses, diversification status,
retrace data, and native symbols. The Gradle gate validates path separation,
required customer files, private-material leakage, and a private immutable
artifact manifest for each component. It never converts the adapter's
assertions into public marketing claims without the separate artifact and
installed-runtime acceptance.

The final candidate task then:

1. writes a reproducible per-component, per-platform release manifest;
2. calls the existing `bootstrap/cmd/sign-manifest` implementation with an
   injected `SYNESIS_MANIFEST_PRIVATE_KEY_B64` secret; and
3. verifies the detached signature against the public key compiled into the
   bootstrapper.

The adapter request, result, configuration, seed, mappings, retrace data,
native symbols, capability evidence, manifest, and signature are kept outside the
customer archive except for the signed manifest and detached signature that
the existing distribution flow needs. Adapter output is discarded from normal
logs to reduce accidental disclosure.

The request is bound to the current source-scoped Tier 0–3 inventory and its
SHA-256 digest, the narrow keep-rule inventory, and the protected acceptance
procedure. The result must echo the release ID, source commit, and inventory
digest. The maximum tasks reject a dirty source checkout so a protected
release cannot be presented as reproducible while silently including local
developer edits.

The adapter executable and protector configuration must also resolve outside
the source checkout. The Gradle gate checks both the lexical path and the
canonical path, preventing a committed file or a symlink alias from being used
as the private commercial tool/configuration boundary.

The customer bundle and private release directory are also required to be
symlink-free. The Gradle gate rejects symbolic links after the adapter returns,
before it walks either tree to create manifests or inspect private-material
leakage. This keeps the lexical path-boundary checks from being bypassed by an
adapter or configuration that resolves a file outside the requested output
root.

Non-empty mapping and retrace files are not sufficient recovery evidence. The
adapter must also provide a private schema-1 retrace-acceptance properties
file. It records a verified retrace test, the retrace tool and test-case
identity, the exact mapping-file SHA-256, and hashes of the input and translated
stack traces. The Gradle gate validates those bindings and retains the evidence
hash in the private release record.

The signer now accepts explicit manifest and signature paths while preserving
its existing defaults. This lets the release task sign a build-directory
candidate without mutating the source checkout or creating a second signing
implementation.

## Consequences

- A real commercial integration can be supplied without changing the normal
  developer build or pretending that ProGuard is maximum protection.
- The tier inventory gives the vendor adapter a reviewed, source-backed target
  boundary without requiring a global `org.synesis.**` keep or whole-system
  virtualization.
- Missing protector, configuration, explicit release ID/seed, signing key,
  any Ring 1–6 evidence, inventory binding, clean release checkout, or
  signature causes a fail-closed result; Ring 7 is then recorded separately by
  the release pipeline and private release record.
- A symbolic link in the customer or private output tree fails the release
  before manifesting or signing; the adapter must emit ordinary files and
  directories within the requested roots.
- A missing or malformed retrace-acceptance record fails the release; a
  non-empty mapping file alone cannot be presented as proven recovery.
- The adapter contract is intentionally vendor-neutral; a release engineer
  still must write or obtain the vendor-specific wrapper and review its exact
  configuration.
- Signing the per-platform candidate does not replace the required aggregate
  release workflow or the protected installed-runtime acceptance.
- The current checkout remains `PARTIAL` because no licensed adapter has been
  exercised.

## Rejected alternatives

- Treating the protection-lite ProGuard output as the maximum profile.
- Implementing a repository-owned virtual machine, packer, anti-debugger, or
  VM detector to fill unavailable commercial capabilities.
- Passing arbitrary shell fragments to Gradle or printing adapter output that
  may contain license or private recovery data.
- Creating a second installer signing root instead of reusing the bootstrap
  signer and trust boundary.
