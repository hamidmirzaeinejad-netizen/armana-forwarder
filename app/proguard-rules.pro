# Components are referenced from the manifest; keep them.
-keep class ir.armana.forwarder.** extends android.app.Service { *; }
-keep class ir.armana.forwarder.** extends android.content.BroadcastReceiver { *; }
-keep class ir.armana.forwarder.** extends android.app.Activity { *; }
-keep class ir.armana.forwarder.** extends android.app.Application { *; }
