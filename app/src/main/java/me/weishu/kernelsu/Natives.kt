package me.weishu.kernelsu

/**
 * RootSentinel seam: KernelSU's Natives talks to its kernel driver. RootSentinel
 * has no kernel side; the only member the copied UI references is
 * isFullFeatured(), which gates whether all bottom-bar destinations show.
 * RootSentinel shows all of its destinations, so this returns true.
 */
object Natives {
    fun isFullFeatured(): Boolean = true

    /** RootSentinel is not a KernelSU manager app. */
    val isManager: Boolean = false

    /** No KernelSU kernel is managed by RootSentinel. */
    val version: Int? = null
}
