# Shell.run reaches Shizuku.newProcess by reflection (the API made it private), so R8 must keep it.
-keep class rikka.shizuku.Shizuku { *; }
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }
# PadOff runs through app_process (Inputd.padOff), which looks up main by name.
-keep class com.xplsh.onnshell.data.PadOff { public static void main(java.lang.String[]); }
# Shizuku loads the user service by class name (no-arg constructor) and binds through the AIDL stub.
-keep class com.xplsh.onnshell.display.BufferBridge { *; }
-keep class com.xplsh.onnshell.display.IBufferBridge** { *; }
