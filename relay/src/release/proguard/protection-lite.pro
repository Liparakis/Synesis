# Protection-lite only. This is not control-flow obfuscation, virtualization,
# string encryption, protected packing, anti-VM, or anti-debugging.
-dontoptimize
-dontusemixedcaseclassnames
-dontnote
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,SourceFile,LineNumberTable
-keep class org.synesis.relay.RelayMain {
    public static void main(java.lang.String[]);
}
-keepclassmembers class * {
    native <methods>;
}
-dontwarn javax.crypto.**
