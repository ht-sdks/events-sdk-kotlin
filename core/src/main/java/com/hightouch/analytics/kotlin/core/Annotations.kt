package com.hightouch.analytics.kotlin.core

@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "This method invokes `runBlocking` internal, it's not recommended to be used in coroutines."
)
annotation class BlockingApi

@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This API is for Hightouch SDK modules only. Apps using the push SDK should call HightouchPush instead."
)
annotation class InternalHightouchApi