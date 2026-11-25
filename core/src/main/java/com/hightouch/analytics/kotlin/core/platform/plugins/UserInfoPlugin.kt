package com.hightouch.analytics.kotlin.core.platform.plugins

import com.hightouch.analytics.kotlin.core.*
import com.hightouch.analytics.kotlin.core.platform.Plugin

/**
 * Analytics plugin used to populate events with basic UserInfo data.
 * Auto-added to analytics client on construction
 */
class UserInfoPlugin : Plugin {

    override val type: Plugin.Type = Plugin.Type.Before
    override lateinit var analytics: Analytics

    @OptIn(InternalHightouchApi::class)
    override fun execute(event: BaseEvent): BaseEvent {

        if (event.type == EventType.Identify) {
            if (analytics.dataCollectionEnabled) {
                analytics.userInfo = analytics.userInfo.copy(
                    userId = event.userId,
                    anonymousId = event.anonymousId,
                    traits = (event as IdentifyEvent).traits
                )
            }
        } else if (event.type === EventType.Alias) {
            if (analytics.dataCollectionEnabled) {
                analytics.userInfo = analytics.userInfo.copy(
                    anonymousId = event.anonymousId
                )
            }
        } else {
            val current = analytics.userInfo
            current.userId?.let {
                event.userId = it
            }
            event.anonymousId = current.anonymousId
        }

        return event
    }

}

