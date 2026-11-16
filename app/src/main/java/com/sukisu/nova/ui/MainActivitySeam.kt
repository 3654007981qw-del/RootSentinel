package com.sukisu.nova.ui

/**
 * RootSentinel seam: vendored SukiSU-Nova code builds intents and component
 * names against upstream's own MainActivity (not vendored — RootSentinel
 * keeps its single MainActivity). This typealias binds those references
 * to the real activity class.
 */
typealias MainActivity = com.rootsentinel.MainActivity
