# R8 rules for release builds. Most libraries ship their own consumer rules; these cover gaps
# seen on a device.

# ML Kit discovers its components by reflection (Firebase-style ComponentRegistrar with a
# no-arg constructor). R8 full mode removed those constructors in the 2026-10-18 release
# build ("Could not instantiate ...BarcodeRegistrar"), which breaks barcode and label scanning.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.**.*Registrar { <init>(); }
