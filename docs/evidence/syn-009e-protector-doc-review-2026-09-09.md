# SYN-009E commercial protector documentation review — 2026-09-09

Status: `DOCUMENTATION_ONLY / NO SYNESIS ARTIFACT EXECUTION`

This is a first-party documentation pre-screen performed to refine the
commercial candidate matrix. It does not select a vendor, satisfy a Seven
Ring, or convert any `UNVERIFIED` matrix cell to `PASS`. A licensed tool,
version-pinned configuration, protected Synesis artifact, and shipped-artifact
acceptance are still required.

## DashO

The current PreEmptive changelog records Java 25 bytecode/JEP support in DashO
12.6, Java 26 bytecode support in 12.8, Gradle 8.14.2 support, and runtime
application self-protection improvements. The project-file reference documents
scoped control-flow obfuscation and string encryption, and the product guide
documents post-compile CLI/Gradle integration.

Sources:

- [DashO changelog](https://support.preemptive.com/hc/en-us/articles/31997866992017-Changelog)
- [DashO project-file reference](https://support.preemptive.com/hc/en-us/articles/32030139763473-Project-File-Reference)
- [DashO Java/Gradle integration overview](https://account.preemptive.com/dasho/pro/10.0/userguide/en/index.html)

The reviewed first-party material does not establish genuine JVM code
virtualization or a protected customer payload loader. It also does not prove
Synesis compatibility with Java 25 records/modules, reflection,
ServiceLoader, JNI/native QUIC, Link, relay, provider flows, or the safe
legitimate-VM/debugger policy required here. Those remain execution gates.

## Zelix KlassMaster

Zelix KlassMaster 26.0 documents processing Java bytecode through version 26,
command-line script execution, and Gradle integration. Its obfuscation
documentation describes flow obfuscation, string/integer/long constant
encryption, reference/reflection handling, and change-log/stack-trace
translation. The documentation index also includes a reproducible-obfuscated
output tutorial.

Sources:

- [Zelix installation and bytecode compatibility](https://www.zelix.com/klassmaster/docs/install.html)
- [Zelix obfuscation options](https://www.zelix.com/klassmaster/docs/obfuscateOptions.html)
- [Zelix obfuscate statement](https://www.zelix.com/klassmaster/docs/obfuscateStatement.html)
- [Zelix documentation index](https://zelix.com/klassmaster/docs/index.html)

The reviewed first-party material does not establish genuine virtual execution,
a protected payload loader/packing mechanism, or Synesis-safe
anti-debug/instrumentation behavior. Exact Java 25 Gradle/native/Link
acceptance, shipped artifact inspection, seeded diversification, and private
retrace remain unexecuted.

## Selection consequence

DashO is a credible first candidate for a licensed Java 25/Gradle evaluation
because the reviewed changelog explicitly records Java 25 bytecode support and
the product documents control-flow, string encryption, and runtime protection.
Zelix remains a credible flow/string/constant/reproducibility candidate with
documented Java 26 bytecode handling. Neither candidate is selected as the
maximum protector, and neither documentation set supplies evidence for Ring 2
virtualization or Ring 5 protected loading. The maximum adapter must therefore
remain fail-closed until a licensed candidate proves every required ring and
the shipped artifact survives Synesis acceptance.
