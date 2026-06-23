package com.hightouch.analytics.kotlin.push.internal

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.hightouch.analytics.kotlin.push.HightouchAction
import com.hightouch.analytics.kotlin.push.HightouchActionContext
import com.hightouch.analytics.kotlin.push.HightouchActionSource
import com.hightouch.analytics.kotlin.push.HightouchCustomActionHandler
import com.hightouch.analytics.kotlin.push.HightouchPush
import com.hightouch.analytics.kotlin.push.HightouchPushConfig
import com.hightouch.analytics.kotlin.push.HightouchUrlHandler
import com.hightouch.analytics.kotlin.core.Analytics
import com.hightouch.analytics.kotlin.core.Configuration
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ActionRouterTest {

    private lateinit var context: Context
    private lateinit var analytics: Analytics

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        HightouchPush.resetForTesting()
        analytics = mockAnalytics(context)
    }

    @After
    fun tearDown() {
        HightouchPush.resetForTesting()
    }

    @Test
    fun `body tap fires opened engagement event with default identifier`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())
        val captured = slot<JsonObject>()
        every { analytics.track("CEP Engagement Events", capture(captured)) } returns Unit

        ActionRouter.route(context, bundle(messageId = "m-1"))

        val props = captured.captured
        assertEquals("opened", props["provider_event_type"]?.jsonPrimitive?.content)
        assertEquals(
            "default",
            props["action"]?.jsonObject?.get("identifier")?.jsonPrimitive?.content,
        )
        assertEquals("m-1", props["message_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `action button tap fires opened with the button identifier`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())
        val captured = slot<JsonObject>()
        every { analytics.track("CEP Engagement Events", capture(captured)) } returns Unit

        ActionRouter.route(context, bundle(messageId = "m-2", sourceButtonId = "yes"))

        val props = captured.captured
        assertEquals(
            "yes",
            props["action"]?.jsonObject?.get("identifier")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `messageContext is merged into the engagement event with documented precedence`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())
        val captured = slot<JsonObject>()
        every { analytics.track("CEP Engagement Events", capture(captured)) } returns Unit

        ActionRouter.route(
            context,
            bundle(
                messageId = "m-3",
                messageContextJson = """{"campaign_id":"c1","app_id":"context-app-id"}""",
            ),
        )

        val props = captured.captured
        assertEquals("c1", props["campaign_id"]?.jsonPrimitive?.content)
        // baseProperties.app_id beats messageContext.app_id
        assertEquals("app-1", props["app_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `openUrl action invokes the url handler with the parsed Uri`() {
        val urlHandler = mockk<HightouchUrlHandler>(relaxed = true)
        every { urlHandler.handleUrl(any(), any()) } returns true
        HightouchPush.initialize(
            analytics,
            HightouchPushConfig.Builder("app-1").setUrlHandler(urlHandler).build(),
        )

        ActionRouter.route(
            context,
            bundle(messageId = "m-4", actionType = "openUrl", actionData = "https://example.com/promo"),
        )

        val urlSlot = slot<Uri>()
        val ctxSlot = slot<HightouchActionContext>()
        verify { urlHandler.handleUrl(capture(urlSlot), capture(ctxSlot)) }
        assertEquals("https://example.com/promo", urlSlot.captured.toString())
        assertEquals(HightouchActionSource.Push, ctxSlot.captured.source)
    }

    @Test
    fun `openUrl falls back to startActivity when handler returns false`() {
        val urlHandler = mockk<HightouchUrlHandler>(relaxed = true)
        every { urlHandler.handleUrl(any(), any()) } returns false
        HightouchPush.initialize(
            analytics,
            HightouchPushConfig.Builder("app-1").setUrlHandler(urlHandler).build(),
        )

        ActionRouter.route(
            context,
            bundle(messageId = "m-5", actionType = "openUrl", actionData = "https://example.com"),
        )

        val launched = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull(launched)
        assertEquals(Intent.ACTION_VIEW, launched.action)
        assertEquals("https://example.com", launched.data.toString())
    }

    @Test
    fun `openUrl fallback rejects non-https schemes not on the allowed list`() {
        HightouchPush.initialize(
            analytics,
            HightouchPushConfig.Builder("app-1").build(), // no allowedProtocols
        )

        ActionRouter.route(
            context,
            bundle(messageId = "m-6", actionType = "openUrl", actionData = "myapp://deeplink"),
        )

        val launched = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        assertNull(launched)
    }

    @Test
    fun `openUrl fallback accepts schemes listed in allowedProtocols`() {
        HightouchPush.initialize(
            analytics,
            HightouchPushConfig.Builder("app-1").setAllowedProtocols(listOf("myapp")).build(),
        )

        ActionRouter.route(
            context,
            bundle(messageId = "m-7", actionType = "openUrl", actionData = "myapp://deeplink"),
        )

        val launched = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull(launched)
        assertEquals("myapp://deeplink", launched.data.toString())
    }

    @Test
    fun `non-openUrl action types route to the custom action handler`() {
        val customActionHandler = mockk<HightouchCustomActionHandler>(relaxed = true)
        HightouchPush.initialize(
            analytics,
            HightouchPushConfig.Builder("app-1").setCustomActionHandler(customActionHandler).build(),
        )

        ActionRouter.route(
            context,
            bundle(
                messageId = "m-8",
                actionType = "openLevel",
                actionData = "12",
                sourceButtonId = "play",
            ),
        )

        val actionSlot = slot<HightouchAction>()
        val ctxSlot = slot<HightouchActionContext>()
        verify { customActionHandler.handleAction(capture(actionSlot), capture(ctxSlot)) }
        assertEquals("openLevel", actionSlot.captured.type)
        assertEquals("12", actionSlot.captured.data)
        assertEquals(
            HightouchActionSource.ActionButton("play"),
            ctxSlot.captured.source,
        )
    }

    @Test
    fun `route is a no-op when message_id is missing`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())

        ActionRouter.route(context, Bundle().apply { putString("unrelated", "x") })

        verify(exactly = 0) { analytics.track("CEP Engagement Events", any<JsonObject>()) }
    }

    @Test
    fun `route is a no-op when SDK is uninitialized`() {
        // No initialize call.
        ActionRouter.route(context, bundle(messageId = "m-x"))
        // Should not throw.
    }

    @Test
    fun `urlHandler-returns-false drops the URL when no fallback scheme matches`() {
        val urlHandler = mockk<HightouchUrlHandler>(relaxed = true)
        every { urlHandler.handleUrl(any(), any()) } returns false
        HightouchPush.initialize(
            analytics,
            HightouchPushConfig.Builder("app-1").setUrlHandler(urlHandler).build(),
        )

        ActionRouter.route(
            context,
            bundle(messageId = "m-9", actionType = "openUrl", actionData = "ftp://example.com"),
        )

        verify { urlHandler.handleUrl(any(), any()) }
        assertNull(Shadows.shadowOf(context as android.app.Application).nextStartedActivity)
    }

    @Test
    fun `openUrl with non-parseable URI is dropped`() {
        val urlHandler = mockk<HightouchUrlHandler>(relaxed = true)
        HightouchPush.initialize(
            analytics,
            HightouchPushConfig.Builder("app-1").setUrlHandler(urlHandler).build(),
        )

        ActionRouter.route(
            context,
            bundle(messageId = "m-10", actionType = "openUrl", actionData = null),
        )

        verify(exactly = 0) { urlHandler.handleUrl(any(), any()) }
    }

    @Test
    fun `non-openUrl with no custom handler is silently dropped`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())
        // Should not throw — engagement event still fires, the action just goes nowhere.
        val captured = slot<JsonObject>()
        every { analytics.track("CEP Engagement Events", capture(captured)) } returns Unit

        ActionRouter.route(
            context,
            bundle(messageId = "m-11", actionType = "playSound", actionData = "boop"),
        )

        // Engagement event fired
        assertTrue(captured.isCaptured)
    }

    @Test
    fun `body tap with no action launches the host app`() {
        registerLauncherActivity()
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())

        ActionRouter.route(context, bundle(messageId = "m-open"))

        assertNotNull(nextStartedActivity())
    }

    @Test
    fun `launch intent carries customData for the opened app to read`() {
        registerLauncherActivity()
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())

        ActionRouter.route(
            context,
            bundle(messageId = "m-open", customDataJson = """{"order_id":"42"}"""),
        )

        val launched = nextStartedActivity()
        assertNotNull(launched)
        assertEquals(mapOf("order_id" to "42"), HightouchPush.getCustomData(launched!!))
    }

    @Test
    fun `openApp=false action receiver never launches the app`() {
        registerLauncherActivity()
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())

        ActionRouter.route(
            context,
            bundle(messageId = "m", sourceButtonId = "archive", fromActionReceiver = true),
        )

        assertNull(nextStartedActivity())
    }

    @Test
    fun `autoLaunchApp=false suppresses the launcher fallback`() {
        registerLauncherActivity()
        HightouchPush.initialize(
            analytics,
            HightouchPushConfig.Builder("app-1").setAutoLaunchApp(false).build(),
        )

        ActionRouter.route(context, bundle(messageId = "m-open"))

        assertNull(nextStartedActivity())
    }

    @Test
    fun `handled deep link does not also launch the app`() {
        registerLauncherActivity()
        val urlHandler = mockk<HightouchUrlHandler>(relaxed = true)
        every { urlHandler.handleUrl(any(), any()) } returns true
        HightouchPush.initialize(
            analytics,
            HightouchPushConfig.Builder("app-1").setUrlHandler(urlHandler).build(),
        )

        ActionRouter.route(
            context,
            bundle(messageId = "m", actionType = "openUrl", actionData = "https://example.com"),
        )

        assertNull(nextStartedActivity())
    }

    @Test
    fun `customData is surfaced on the action context passed to handlers`() {
        val urlHandler = mockk<HightouchUrlHandler>(relaxed = true)
        every { urlHandler.handleUrl(any(), any()) } returns true
        HightouchPush.initialize(
            analytics,
            HightouchPushConfig.Builder("app-1").setUrlHandler(urlHandler).build(),
        )

        ActionRouter.route(
            context,
            bundle(
                messageId = "m",
                actionType = "openUrl",
                actionData = "https://example.com",
                customDataJson = """{"k":"v"}""",
            ),
        )

        val ctxSlot = slot<HightouchActionContext>()
        verify { urlHandler.handleUrl(any(), capture(ctxSlot)) }
        assertEquals(mapOf("k" to "v"), ctxSlot.captured.customData)
    }

    private fun bundle(
        messageId: String,
        sourceButtonId: String? = null,
        actionType: String? = null,
        actionData: String? = null,
        messageContextJson: String? = null,
        customDataJson: String? = null,
        fromActionReceiver: Boolean = false,
    ): Bundle = Bundle().apply {
        putString(IntentExtras.MESSAGE_ID, messageId)
        sourceButtonId?.let { putString(IntentExtras.SOURCE_BUTTON_ID, it) }
        actionType?.let { putString(IntentExtras.ACTION_TYPE, it) }
        actionData?.let { putString(IntentExtras.ACTION_DATA, it) }
        messageContextJson?.let { putString(IntentExtras.MESSAGE_CONTEXT_JSON, it) }
        customDataJson?.let { putString(IntentExtras.CUSTOM_DATA_JSON, it) }
        if (fromActionReceiver) putBoolean(IntentExtras.FROM_ACTION_RECEIVER, true)
    }

    /** Registers a launcher activity so `getLaunchIntentForPackage` resolves in the open-app tests. */
    private fun registerLauncherActivity() {
        val component = ComponentName(context.packageName, "${context.packageName}.MainActivity")
        val pm = Shadows.shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    private fun nextStartedActivity(): Intent? =
        Shadows.shadowOf(context as Application).nextStartedActivity

    private fun mockAnalytics(appContext: Context): Analytics {
        val configuration = mockk<Configuration>(relaxed = true)
        every { configuration.application } returns appContext
        return mockk<Analytics>(relaxed = true).also {
            every { it.configuration } returns configuration
            every { it.userId() } returns null
            every { it.anonymousId() } returns "anon"
        }
    }
}
