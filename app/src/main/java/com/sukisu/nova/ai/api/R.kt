package com.sukisu.nova.ai.api

/**
 * RootSentinel seam for the ai-api module's R (imported upstream as AiR).
 * Only the module's two strings are referenced by the vendored UI; they
 * live unprefixed in RootSentinel's resources (no name collisions).
 * NOTE: sukisu_sync.py skips this file.
 */
object R {
    object string {
        val ai_show_home_card: Int get() = com.rootsentinel.R.string.ai_show_home_card
        val ai_show_home_card_summary: Int get() = com.rootsentinel.R.string.ai_show_home_card_summary
    }
}
