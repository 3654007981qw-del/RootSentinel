package me.weishu.kernelsu.data.registry

// Copied from KernelSU's RegistryParser.kt (the exception declarations only;
// the registry parser itself serves KernelSU's module registry, which
// RootSentinel does not use — RegistryUrls.kt, copied verbatim, throws
// RegistryFormatException).

open class RegistryException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The document is not valid JSON or violates the protocol. */
class RegistryFormatException(message: String, cause: Throwable? = null) : RegistryException(message, cause)
