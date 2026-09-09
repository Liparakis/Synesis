# SYN-009E protection-lite baseline.
# This file is a free compatibility/hardening profile, not the commercial
# maximum profile. Standard ProGuard optimization and mixed-case renaming may
# reduce recoverable structure, but they must never be used to claim
# virtualization, protected packing, anti-VM, or anti-debugging.

-dontnote
-keepdirectories

# The standalone CLI, dynamic MCP bridge, and future relay profile are stable
# process entrypoints. The MCP class name is required by Class.forName in
# cli/.../McpCommand.java; the method signature is invoked reflectively.
-keep public class org.synesis.cli.SynesisCli {
    public static void main(java.lang.String[]);
}
-keep public class org.synesis.mcp.SynesisMcpServer {
    public static int execute(java.lang.String[]);
}
# Picocli discovers annotated fields and command metadata reflectively. Keep
# only the annotated members and metadata; command implementation classes are
# otherwise eligible for shrinking/renaming through ordinary reachability.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod,Signature,Exceptions,MethodParameters
-keepclassmembers,includedescriptorclasses class * {
    @picocli.CommandLine$Option <fields>;
    @picocli.CommandLine$Parameters <fields>;
    @picocli.CommandLine$Spec <fields>;
    @picocli.CommandLine$ParentCommand <fields>;
    @picocli.CommandLine$Mixin <fields>;
    @picocli.CommandLine$Unmatched <fields>;
    @picocli.CommandLine$ArgGroup <fields>;
}
-keepclassmembers enum * {
    *;
}
-keep,allowoptimization,allowobfuscation @picocli.CommandLine$Command class * {
    <init>(...);
}

# Repackage transformed implementation classes into one neutral namespace.
# Explicitly kept entrypoints and runtime contracts remain stable; this reduces
# recoverable module/package structure without claiming control-flow protection.
-repackageclasses org.synesis.p

# Do not retain source-file or line-number attributes in the customer-facing
# lite artifact. The private mapping remains available for name recovery, but
# source-location metadata is intentionally not shipped.

# The current workstation has a Java 25 runtime image but no Java 25 JMOD
# directory. The release task accepts an explicit compatible JMOD source (the
# current local fallback is Java 21) and records it privately. These two
# packages contain newer Java 25 APIs used by source code; the runtime smoke
# still executes against the Java 25 image, and no application warning is
# suppressed broadly.
-dontwarn javax.crypto.**
-dontwarn java.lang.foreign.**

# The UI, build metadata, service registrations, native-resource descriptors,
# and explicit JSON/properties resources are runtime inputs, not class names.
# ProGuard's open-source configuration keeps input resources by default; keeping
# directory entries preserves classpath/resource lookups without a broad class
# keep. The protected-bundle acceptance verifies the concrete resource set.
-keepdirectories web-ui/**
-keepdirectories META-INF/services/**
-keepdirectories META-INF/native/**

# Preserve JNI/native method names if a future owned component introduces one;
# this is narrower than keeping all application classes.
-keepclassmembers,allowoptimization,allowobfuscation class * {
    native <methods>;
}
