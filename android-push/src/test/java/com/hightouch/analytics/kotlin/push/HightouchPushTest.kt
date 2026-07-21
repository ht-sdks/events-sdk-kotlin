package com.hightouch.analytics.kotlin.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hightouch.analytics.kotlin.push.internal.PushPreferences
import com.hightouch.analytics.kotlin.core.Analytics
import com.hightouch.analytics.kotlin.core.Configuration
import com.hightouch.analytics.kotlin.core.TrackEvent
import com.hightouch.analytics.kotlin.core.platform.EnrichmentClosure
import com.hightouch.analytics.kotlin.core.platform.plugins.DeviceToken
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
            analytics.track("CEP Push Token Events", capture(capturedProps), any())
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
        verify(exactly = 2) { analytics.track("CEP Push Token Events", any<JsonObject>(), any()) }
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
        every { analytics.track("CEP Push Token Events", capture(captured), any()) } returns Unit

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
        every { analytics.track("CEP Push Token Events", capture(captured), any()) } returns Unit

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
        }, any()) }
        verify(exactly = 0) { analytics.reset() }
    }

    @Test
    fun `register before initialize throws`() {
        assertThrows(IllegalStateException::class.java) {
            HightouchPush.register("token")
        }
    }

    @Test
    fun `register stamps lastUploadedAtMillis`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())
        val before = System.currentTimeMillis()

        HightouchPush.register("fcm-token-xyz")

        val stamped = PushPreferences(context).lastUploadedAtMillis
        assertTrue("expected lastUploadedAtMillis to be stamped", stamped >= before)
    }

    @Test
    fun `register while analytics is disabled clears the heartbeat stamp so the next heartbeat retries`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())
        HightouchPush.register("fcm-token-xyz")
        assertTrue(PushPreferences(context).lastUploadedAtMillis > 0)

        // process() drops events silently while analytics is disabled; the stamp must not
        // claim the dropped upload succeeded.
        every { analytics.enabled } returns false
        HightouchPush.register("fcm-token-2")

        assertEquals("fcm-token-2", PushPreferences(context).token)
        assertEquals(0L, PushPreferences(context).lastUploadedAtMillis)
    }

    @Test
    fun `repeat identify with the same user does not emit a duplicate registered event`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())
        HightouchPush.register("fcm-token-xyz")

        HightouchPush.identify("user-1") // user change: forced re-register
        HightouchPush.identify("user-1") // unchanged: gated, token fresh, no event

        verify(exactly = 2) { analytics.track("CEP Push Token Events", any<JsonObject>(), any()) }
    }

    @Test
    fun `register stamps the registered event with the current userId`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())
        HightouchPush.identify("user-1") // no token cached yet, so no register fires here

        val enrichmentSlot = slot<EnrichmentClosure>()
        every {
            analytics.track("CEP Push Token Events", any<JsonObject>(), capture(enrichmentSlot))
        } returns Unit

        HightouchPush.register("fcm-token-xyz")

        // Run the closure as the pipeline would (after UserInfoPlugin) — it must pin the userId
        // captured at register() time even if the store hasn't applied SetUserIdAction yet.
        val event = TrackEvent(properties = JsonObject(emptyMap()), event = "CEP Push Token Events")
        val enriched = enrichmentSlot.captured(event) as TrackEvent
        assertEquals("user-1", enriched.userId)
    }

    @Test
    fun `logout stamps the disabled event with the pre-reset identity`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())
        HightouchPush.register("fcm-token-xyz")
        HightouchPush.identify("user-1")

        val enrichmentSlot = slot<EnrichmentClosure>()
        every {
            analytics.track("CEP Push Token Events", any<JsonObject>(), capture(enrichmentSlot))
        } returns Unit

        HightouchPush.logout()

        // reset() swaps in a new anonymousId immediately; the enrichment must restore the
        // outgoing identity captured before the reset.
        val event = TrackEvent(properties = JsonObject(emptyMap()), event = "CEP Push Token Events")
        event.anonymousId = "post-reset-anon"
        val enriched = enrichmentSlot.captured(event) as TrackEvent
        assertEquals("user-1", enriched.userId)
        assertEquals("anon-123", enriched.anonymousId)
    }

    // MARK: - stale fetched-token guard

    @Test
    fun `registerFetchedTokenIfDue drops a fetch snapshot that raced a newer registration`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())
        // A token fetch started when no token was stored yet...
        val tokenAtFetchStart: String? = null
        // ...then onNewToken registered a fresh token while the fetch was in flight.
        HightouchPush.register("fresh-token")

        HightouchPush.registerFetchedTokenIfDue("stale-token", tokenAtFetchStart)

        assertEquals("fresh-token", PushPreferences(context).token)
    }

    @Test
    fun `registerFetchedTokenIfDue registers when no registration raced the fetch`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())

        HightouchPush.registerFetchedTokenIfDue("fetched-token", null)

        assertEquals("fetched-token", PushPreferences(context).token)
    }

    // MARK: - foreground heartbeat observer

    @Test
    fun `initialize registers the foreground heartbeat observer`() {
        assertFalse(HightouchPush.hasForegroundHeartbeatObserver)

        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())

        assertTrue(HightouchPush.hasForegroundHeartbeatObserver)
    }

    @Test
    fun `resetForTesting clears the foreground heartbeat observer`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())
        assertTrue(HightouchPush.hasForegroundHeartbeatObserver)

        HightouchPush.resetForTesting()

        assertFalse(HightouchPush.hasForegroundHeartbeatObserver)
    }

    // MARK: - shouldUploadToken (cold-start dedupe + heartbeat decision)

    private val interval = TimeUnit.HOURS.toMillis(24)

    @Test
    fun `shouldUploadToken uploads when token changed even within interval`() {
        assertTrue(
            HightouchPush.shouldUploadToken(
                incomingToken = "new-token",
                lastUploadedToken = "old-token",
                lastUploadedAtMillis = 1_000L,
                nowMillis = 1_000L,
                intervalMillis = interval,
            ),
        )
    }

    @Test
    fun `shouldUploadToken skips when token unchanged and within interval`() {
        val now = interval * 10
        assertFalse(
            HightouchPush.shouldUploadToken(
                incomingToken = "same-token",
                lastUploadedToken = "same-token",
                lastUploadedAtMillis = now - (interval - 1),
                nowMillis = now,
                intervalMillis = interval,
            ),
        )
    }

    @Test
    fun `shouldUploadToken uploads when token unchanged but interval elapsed`() {
        val now = interval * 10
        assertTrue(
            HightouchPush.shouldUploadToken(
                incomingToken = "same-token",
                lastUploadedToken = "same-token",
                lastUploadedAtMillis = now - interval,
                nowMillis = now,
                intervalMillis = interval,
            ),
        )
    }

    @Test
    fun `shouldUploadToken uploads when clock rolled back past the last upload stamp`() {
        val now = interval * 10
        assertTrue(
            HightouchPush.shouldUploadToken(
                incomingToken = "same-token",
                lastUploadedToken = "same-token",
                lastUploadedAtMillis = now + 1, // stamped while the wall clock was ahead
                nowMillis = now,
                intervalMillis = interval,
            ),
        )
    }

    @Test
    fun `shouldUploadToken uploads when never uploaded before`() {
        // lastUploadedAtMillis == 0 (the PushPreferences default) always uploads because a real
        // epoch `now` dwarfs any interval.
        assertTrue(
            HightouchPush.shouldUploadToken(
                incomingToken = "same-token",
                lastUploadedToken = "same-token",
                lastUploadedAtMillis = 0L,
                nowMillis = System.currentTimeMillis(),
                intervalMillis = interval,
            ),
        )
    }

    @Test
    fun `shouldUploadToken uploads when no prior token recorded`() {
        val now = interval * 10
        assertTrue(
            HightouchPush.shouldUploadToken(
                incomingToken = "first-token",
                lastUploadedToken = null,
                lastUploadedAtMillis = now,
                nowMillis = now,
                intervalMillis = interval,
            ),
        )
    }

    // MARK: - HightouchPushConfig heartbeat interval clamp

    @Test
    fun `config defaults token upload interval to 24h`() {
        val config = HightouchPushConfig.Builder("app-1").build()
        assertEquals(TimeUnit.HOURS.toMillis(24), config.tokenUploadIntervalMillis)
    }

    @Test
    fun `config clamps sub-minimum interval up to 12h`() {
        val config = HightouchPushConfig.Builder("app-1")
            .setTokenUploadInterval(TimeUnit.MINUTES.toMillis(5))
            .build()
        assertEquals(TimeUnit.HOURS.toMillis(12), config.tokenUploadIntervalMillis)
    }

    @Test
    fun `config preserves interval at or above the minimum`() {
        val sevenDays = TimeUnit.DAYS.toMillis(7)
        val config = HightouchPushConfig.Builder("app-1")
            .setTokenUploadInterval(sevenDays)
            .build()
        assertEquals(sevenDays, config.tokenUploadIntervalMillis)
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
            // A relaxed mock returns false for Booleans; register() gates the heartbeat stamp
            // on enabled, so mirror the real default.
            every { it.enabled } returns true
            // setDeviceToken does `find(DeviceToken::class) as DeviceToken`. With a relaxed mock,
            // find() returns a generic Plugin mock from MockK's classloader; the cast then fails
            // with ClassCastException under Robolectric's separate classloader. Force null so
            // setDeviceToken takes the create-and-add branch instead.
            every { it.find(DeviceToken::class) } returns null
        }
    }
}
