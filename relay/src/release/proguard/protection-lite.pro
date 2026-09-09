# Protection-lite enables standard ProGuard optimization and mixed-case
# renaming, but it is not control-flow obfuscation, virtualization, string
# encryption, protected packing, anti-VM, or anti-debugging.
-dontnote
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions
-keep class org.synesis.relay.RelayMain {
    public static void main(java.lang.String[]);
}
-keepclassmembers class * {
    native <methods>;
}
-dontwarn javax.crypto.**
