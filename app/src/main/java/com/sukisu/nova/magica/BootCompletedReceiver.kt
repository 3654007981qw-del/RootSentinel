package com.sukisu.nova.magica

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * RootSentinel seam: SukiSU's settings repository references this receiver's
 * ComponentName when toggling a persist flag. RootSentinel registers no such
 * receiver; the class exists so the verbatim repository compiles and the
 * component-name call stays a harmless no-op.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
