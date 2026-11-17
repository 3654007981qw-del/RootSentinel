package com.rootsentinel.data

/**
 * v2.22.80: "switch to the real manager" action. RootSentinel
 * bundles the user-supplied vivo KernelSU manager APK
 * (me.inkdye.vivoksu) as an asset; RootApp points this at a
 * handler that launches the manager when installed, and
 * offers the bundled copy through the system installer when
 * it is not. (A literal single-package merge is impossible:
 * the kernel recognises its manager by package name +
 * signing certificate, so the manager must land as its own
 * app — it just ships inside RootSentinel.)
 */
object ManagerBridge {
    var onSwitch: (() -> Unit)? = null
}
