# SYN-009E relay native-scope evidence — 2026-09-09

This is a local developer-install inspection, not maximum-release evidence.
It records why the maximum adapter contract distinguishes Synesis-owned native
symbols from third-party native dependency auditing.

Observed checkout: `1b218b7c0863dbcbb9e6d07f59d3a502a26d02a7`.

The extracted relay install contained no standalone Synesis-owned `.exe`,
`.dll`, `.so`, or `.dylib` outside dependency archives. It did contain the
third-party Netty QUIC artifacts below:

| Installed file | SHA-256 | Native entries |
|---|---|---|
| `lib/netty-codec-native-quic-4.2.16.Final-windows-x86_64.jar` | `ebe477b5374382f0d34583907b7441dc59c52492c890f946154deff47883041d` | `META-INF/native/netty_quiche42_windows_x86_64.dll` |
| `lib/netty-codec-native-quic-4.2.16.Final.jar` | `cf2d2a587ea7e45f130ccd2a132e70e048979bc20e25efdbf332c2f18abe5230` | no `.dll`, `.so`, or `.dylib` entry observed |

Consequences for a licensed maximum adapter:

- `cli` must report `nativeSymbolsScope=owned` and retain non-empty private
  symbols for its Synesis-owned native launchers.
- `relay` must report `nativeSymbolsScope=not-applicable` for Synesis-owned
  native symbols, retain `nativeSymbolsDirectory=not-applicable`, and supply a
  non-empty private audit of every shipped third-party native dependency.
- If the commercial protector adds a native protected loader to the relay
  output, it must instead report `nativeSymbolsScope=owned` and supply non-empty
  private symbols for that output.
- The adapter must not rewrite the Netty native code. The private audit must
  identify the dependency inputs and their hashes; final acceptance must still
  inspect the exact protected archive and installed relay.

The Gradle maximum tasks and provenance comparison now enforce this scope
explicitly. No commercial adapter, signed maximum archive, or positive
third-party audit record was supplied in this checkout.
