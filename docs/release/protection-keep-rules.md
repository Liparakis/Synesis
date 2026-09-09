# Protection keep-rule inventory

Keep rules are narrow compatibility contracts. They are reviewed against the
current source and runtime seams; a global `org.synesis.**` keep is prohibited.

| Boundary | Current contract | Lite treatment | Evidence/reason |
| --- | --- | --- | --- |
| CLI entrypoint | `org.synesis.cli.SynesisCli.main(String[])` is launched by the bundle scripts | class and `main` name kept | launcher invokes the stable class name |
| MCP bridge | `org.synesis.mcp.SynesisMcpServer.execute(String[])` is called through the CLI bridge | class and `execute` kept | `McpCommand` uses reflective loading and invocation |
| Picocli command tree | command annotations, option/parameter/spec/parent/mixin metadata are read at runtime | annotation attributes and command constructors/members kept narrowly | CLI parsing must work after transformation |
| Picocli enum options | enum descriptors and constants are reflected by Picocli | enum members kept | the first staged run exposed an NPE here; the rule is now covered by the protected acceptance |
| QUIC readiness | `ReadinessInspector` loads `io.netty.handler.codec.quic.QuicChannel` by name | external Netty remains a library input; the reflective class boundary is not renamed by Synesis | readiness must distinguish unavailable native transport from a missing class |
| UI resources | `StaticResourceHandler` resolves `web-ui/` from the classpath | resource root and packaged UI entries retained | the archive contains `web-ui/index.html` and UI assets; the bounded UI server starts |
| Native/resource contracts | native launcher/installer files, service metadata, and native method names are external boundaries | resource paths and native methods retained; third-party native artifacts are not rewritten | avoids breaking the Go launcher or Netty native loading |
| Public protocol/configuration | JSON fields, documented identifiers, configuration keys, and resource paths are Tier 0 | no speculative renaming of public data contracts | external consumers are not source-compatible with arbitrary obfuscation |

The current owned-source scan found no additional `ServiceLoader` registration
or JNI declaration requiring a broader rule. That statement is scoped to the
selected owned inputs; a commercial protector review must repeat the scan for
its complete input set.

The lite configuration intentionally does not claim string encryption,
control-flow obfuscation, virtualization, packing, anti-VM, anti-debugging, or
anti-instrumentation. Java verification is retained; `-dontpreverify` is not
used because it produced a real `VerifyError` in the staged Java 25 runtime.
