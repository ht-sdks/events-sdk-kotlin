package com.hightouch.analytics.kotlin.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hightouch.analytics.kotlin.push.internal.PushPreferences
import com.hightouch.analytics.kotlin.core.Analytics
import com.hightouch.analytics.kotlin.core.Configuration
import com.hightouch.analytics.kotlin.core.platform.plugins.DeviceToken
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class HightouchPushTest {

    private lateinit var context: Context
    private lateinit var analytics: Analytics

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // clear prefs from prior tests so token doesn't leak across cases
        PushPreferences(context).clear()
        HightouchPush.resetForTesting()
        analytics = newMockAnalytics(context, userId = null, anonymousId = "anon-123")
    }

    @After
    fun tearDown() {
        HightouchPush.resetForTesting()
        PushPreferences(context).clear()
    }

    @Test
    fun `initialize preserves cached token across SDK restarts`() {
        PushPreferences(context).token = "cached-fcm-token"

        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())

        // After init, the cached token should still be retrievable from preferences.
        assertEquals("cached-fcm-token", PushPreferences(context).token)
    }

    @Test
    fun `register persists token and fires Push Token Registered with expected properties`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())

        val capturedProps = slot<JsonObject>()
        every {
            analytics.track("CEP Push Token Events", capture(capturedProps))
        } returns Unit

        HightouchPush.register("fcm-token-xyz")

        assertEquals("fcm-token-xyz", PushPreferences(context).token)

        val props = capturedProps.captured
        assertEquals("registered", props["provider_event_type"]?.jsonPrimitive?.content)
        assertEquals("fcm-token-xyz", props["token"]?.jsonPrimitive?.content)
        assertEquals("android", props["platform"]?.jsonPrimitive?.content)
        assertEquals("push", props["channel_type"]?.jsonPrimitive?.content)
        assertEquals("push_sdk", props["_ht_cep_source"]?.jsonPrimitive?.content)
        assertEquals("app-1", props["app_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `identify calls analytics-identify and re-fires register when token is cached`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())
        HightouchPush.register("fcm-token-xyz")

        HightouchPush.identify("user-1")

        // analytics.identify called for the new user
        verify { analytics.identify("user-1") }
        // register fires "registered" — once on the initial register, once after identify
        verify(exactly = 2) { analytics.track("CEP Push Token Events", any<JsonObject>()) }
        assertEquals("user-1", HightouchPush.userId)
    }

    @Test
    fun `identify with same userId twice does not invoke logout`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())
        HightouchPush.register("fcm-token-xyz")

        HightouchPush.identify("user-1")
        HightouchPush.identify("user-1")

        // Only ever one reset() — none from the second identify since users match
        verify(exactly = 0) { analytics.reset() }
    }

    @Test
    fun `identify with different userId triggers logout then identifies the new user`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())

        // Capture from before the register call so all token events end up in the list, not
        // just the ones fired by identify.
        val captured = mutableListOf<JsonObject>()
        every { analytics.track("CEP Push Token Events", capture(captured)) } returns Unit

        HightouchPush.register("fcm-token-xyz")
        HightouchPush.identify("user-1")
        HightouchPush.identify("user-2")

        // Events fired (in order): Registered (from register), Registered (after identify user-1),
        // Disabled (from logout during user switch), Registered (after identify user-2)
        val eventTypes = captured.map { it["provider_event_type"]?.jsonPrimitive?.content }
        assertEquals(
            listOf(
                "registered",
                "registered",
                "disabled",
                "registered",
            ),
            eventTypes,
        )
        verify { analytics.reset() }
        assertEquals("user-2", HightouchPush.userId)
    }

    @Test
    fun `logout fires Push Token Disabled with outgoing userId and resets analytics`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())
        HightouchPush.register("fcm-token-xyz")
        HightouchPush.identify("user-1")

        val captured = slot<JsonObject>()
        every { analytics.track("CEP Push Token Events", capture(captured)) } returns Unit

        HightouchPush.logout()

        val props = captured.captured
        assertEquals("disabled", props["provider_event_type"]?.jsonPrimitive?.content)
        assertEquals("user-1", props["userId"]?.jsonPrimitive?.content)
        assertEquals("fcm-token-xyz", props["token"]?.jsonPrimitive?.content)
        verify { analytics.reset() }
        assertNull(HightouchPush.userId)
    }

    @Test
    fun `logout is a no-op when no user is currently identified`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())
        HightouchPush.register("fcm-token-xyz")

        HightouchPush.logout()

        verify(exactly = 0) { analytics.track("CEP Push Token Events", match<JsonObject> {
            it["provider_event_type"]?.jsonPrimitive?.content == "disabled"
        }) }
        verify(exactly = 0) { analytics.reset() }
    }

    @Test
    fun `register before initialize throws`() {
        try {
            HightouchPush.register("token")
            error("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    private fun newMockAnalytics(
        appContext: Context,
        userId: String?,
        anonymousId: String,
    ): Analytics {
        val configuration = mockk<Configuration>(relaxed = true)
        every { configuration.application } returns appContext

        return mockk<Analytics>(relaxed = true).also {
            every { it.configuration } returns configuration
            every { it.userId() } returns userId
            every { it.anonymousId() } returns anonymousId
            // setDeviceToken does `find(DeviceToken::class) as DeviceToken`. With a relaxed mock,
            // find() returns a generic Plugin mock from MockK's classloader; the cast then fails
            // with ClassCastException under Robolectric's separate classloader. Force null so
            // setDeviceToken takes the create-and-add branch instead.
            every { it.find(DeviceToken::class) } returns null
        }
    }
}
