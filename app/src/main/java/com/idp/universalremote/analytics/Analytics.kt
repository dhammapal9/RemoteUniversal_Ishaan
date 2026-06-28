package com.idp.universalremote.analytics

interface Analytics {
    fun event(name: String, params: Map<String, Any?> = emptyMap())
    fun screen(name: String)
    fun setUserProperty(key: String, value: String?)
}

interface CrashReporter {
    fun log(message: String)
    fun recordException(throwable: Throwable)
}

object NoopAnalytics : Analytics {
    override fun event(name: String, params: Map<String, Any?>) = Unit
    override fun screen(name: String) = Unit
    override fun setUserProperty(key: String, value: String?) = Unit
}

object NoopCrashReporter : CrashReporter {
    override fun log(message: String) = Unit
    override fun recordException(throwable: Throwable) = Unit
}
