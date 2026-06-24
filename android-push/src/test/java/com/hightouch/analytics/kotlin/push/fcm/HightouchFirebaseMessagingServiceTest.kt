package com.hightouch.analytics.kotlin.push.fcm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import com.hightouch.analytics.kotlin.push.HightouchPush
import com.hightouch.analytics.kotlin.push.HightouchPushConfig
import com.hightouch.analytics.kotlin.push.internal.PushPreferences
import com.hightouch.analytics.kotlin.core.Analytics
import com.hightouch.analytics.kotlin.core.Configuration
import com.hightouch.analytics.kotlin.core.platform.plugins.DeviceToken
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class HightouchFirebaseMessagingServiceTest {

    private lateinit var context: Context
    private lateinit var analytics: Analytics

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PushPreferences(context).clear()
        HightouchPush.resetForTesting()
        analytics = mockAnalytics(context)
    }

    @After
    fun tearDown() {
        HightouchPush.resetForTesting()
        PushPreferences(context).clear()
    }

    @Test
    fun `handleTokenRefresh forwards the token to HightouchPush register`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())

        HightouchFirebaseMessagingService.handleTokenRefresh("fcm-abc")

        assertEquals("fcm-abc", PushPreferences(context).token)
        verify {
            analytics.track(eq("CEP Push Token Events"), match<JsonObject> {
                it["token"]?.jsonPrimitive?.content == "fcm-abc"
            })
        }
    }

    @Test
    fun `handleTokenRefresh does not crash when SDK is uninitialized`() {
        // No HightouchPush.initialize() call.
        HightouchFirebaseMessagingService.handleTokenRefresh("fcm-abc")
        // Pass — should swallow the IllegalStateException.
    }

    @Test
    fun `handleMessageReceived returns true for a Hightouch payload`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())
        val msg = mockRemoteMessage(mapOf("hightouch" to """{"messageId":"m-1"}"""))

        val claimed = HightouchFirebaseMessagingService.handleMessageReceived(context, msg)

        assertTrue(claimed)
    }

    @Test
    fun `handleMessageReceived returns false for a non-Hightouch payload so host can keep handling`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())
        val msg = mockRemoteMessage(mapOf("other_provider" to """{"campaignId":1}"""))

        val claimed = HightouchFirebaseMessagingService.handleMessageReceived(context, msg)

        assertFalse(claimed)
    }

    @Test
    fun `handleMessageReceived returns false for a malformed Hightouch JSON wrapper`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())
        val msg = mockRemoteMessage(mapOf("hightouch" to "not-json"))

        val claimed = HightouchFirebaseMessagingService.handleMessageReceived(context, msg)

        assertFalse(claimed)
    }

    private fun mockRemoteMessage(data: Map<String, String>): RemoteMessage {
        return mockk<RemoteMessage>(relaxed = true).also {
            every { it.data } returns data
        }
    }

    private fun mockAnalytics(appContext: Context): Analytics {
        val configuration = mockk<Configuration>(relaxed = true)
        every { configuration.application } returns appContext
        return mockk<Analytics>(relaxed = true).also {
            every { it.configuration } returns configuration
            every { it.userId() } returns null
            every { it.anonymousId() } returns "anon"
            // See HightouchPushTest.newMockAnalytics — keeps setDeviceToken's relaxed-mock cast
            // from blowing up under Robolectric's classloader.
            every { it.find(DeviceToken::class) } returns null
        }
    }
}
