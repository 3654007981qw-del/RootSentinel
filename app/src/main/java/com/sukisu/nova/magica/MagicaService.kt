package com.sukisu.nova.magica

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * RootSentinel seam: upstream MagicaService performs SukiSU's late-load
 * jailbreak work. RootSentinel has no such payload; the class exists so
 * the verbatim home screen compiles — starting it is a no-op.
 */
class MagicaService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
