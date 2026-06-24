package com.hightouch.analytics.kotlin.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hightouch.analytics.kotlin.push.internal.PushPreferences
import com.hightouch.analytics.kotlin.core.Analytics
import com.hightouch.analytics.kotlin.core.Configuration
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CepEventTrackingTest {

    private lateinit var context: Context
    private lateinit var analytics: Analytics

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PushPreferences(context).clear()
        HightouchPush.resetForTesting()
        analytics = mockAnalytics(context)
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())
    }

    @After
    fun tearDown() {
        HightouchPush.resetForTesting()
        PushPreferences(context).clear()
    }

    @Test
    fun `mergedProperties applies baseProperties greater than properties greater than messageContext`() {
        val merged = CepEventTracking.mergedProperties(
            properties = buildJsonObject {
                put("provider_event_type", "from-properties")
                put("only_in_properties", "p")
                // baseProperties has app_id="app-1"; properties tries to override but should lose
                put("app_id", "properties-app-id")
            },
            messageContext = buildJsonObject {
                put("provider_event_type", "from-context")        // properties wins
                put("only_in_context", "c")               // unique key survives
                put("channel_type", "context-channel")    // baseProperties wins
                put("app_id", "context-app-id")           // baseProperties wins
            },
        )

        // Highest precedence: baseProperties
        assertEquals("push", merged["channel_type"]?.jsonPrimitive?.content)
        assertEquals("push_sdk", merged["_ht_cep_source"]?.jsonPrimitive?.content)
        assertEquals("app-1", merged["app_id"]?.jsonPrimitive?.content)
        // Middle: properties overrides messageContext
        assertEquals("from-properties", merged["provider_event_type"]?.jsonPrimitive?.content)
        // Unique keys from each layer survive
        assertEquals("p", merged["only_in_properties"]?.jsonPrimitive?.content)
        assertEquals("c", merged["only_in_context"]?.jsonPrimitive?.content)
    }

    @Test
    fun `baseProperties always contains the canonical CEP fields`() {
        val base = CepEventTracking.baseProperties()
        assertEquals("push", base["channel_type"]?.jsonPrimitive?.content)
        assertEquals("push_sdk", base["_ht_cep_source"]?.jsonPrimitive?.content)
        assertEquals("app-1", base["app_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `track invokes analytics-track with merged properties under the given name`() {
        val captured = slot<JsonObject>()
        every { analytics.track("CEP Engagement Events", capture(captured)) } returns Unit

        CepEventTracking.track(
            name = CepEventTracking.ENGAGEMENT_EVENTS,
            properties = buildJsonObject { put("provider_event_type", "opened") },
            messageContext = buildJsonObject { put("campaign_id", "c1") },
        )

        verify { analytics.track("CEP Engagement Events", any<JsonObject>()) }
        val props = captured.captured
        assertEquals("opened", props["provider_event_type"]?.jsonPrimitive?.content)
        assertEquals("c1", props["campaign_id"]?.jsonPrimitive?.content)
        assertEquals("push", props["channel_type"]?.jsonPrimitive?.content)
        assertEquals("app-1", props["app_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `track is a no-op when the SDK has not been initialized`() {
        HightouchPush.resetForTesting()
        // Should not throw; should not call any analytics method.
        CepEventTracking.track(
            name = CepEventTracking.ENGAGEMENT_EVENTS,
            properties = buildJsonObject { put("provider_event_type", "opened") },
        )
    }

    private fun mockAnalytics(appContext: Context): Analytics {
        val configuration = mockk<Configuration>(relaxed = true)
        every { configuration.application } returns appContext
        return mockk<Analytics>(relaxed = true).also {
            every { it.configuration } returns configuration
            every { it.userId() } returns null
            every { it.anonymousId() } returns "anon-cep"
        }
    }
}
