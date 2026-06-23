package com.hightouch.analytics.kotlin.push

/** Whether the user tapped the notification body or a named action button. */
sealed class HightouchActionSource {
    /** The user tapped the notification body. */
    object Push : HightouchActionSource()

    /** The user tapped an action button. */
    data class ActionButton(val identifier: String) : HightouchActionSource()
}
