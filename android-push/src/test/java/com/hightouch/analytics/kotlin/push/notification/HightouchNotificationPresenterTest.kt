package com.hightouch.analytics.kotlin.push.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.google.firebase.messaging.RemoteMessage
import com.hightouch.analytics.kotlin.push.HightouchPush
import com.hightouch.analytics.kotlin.push.HightouchPushConfig
import com.hightouch.analytics.kotlin.push.internal.PushPreferences
import com.hightouch.analytics.kotlin.core.Analytics
import com.hightouch.analytics.kotlin.core.Configuration
import io.mockk.every
import io.mockk.mockk
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
@Config(manifest = Config.NONE, sdk = [28])
class HightouchNotificationPresenterTest {

    private lateinit var context: Context
    private lateinit var nm: NotificationManager
    private lateinit var analytics: Analytics

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        PushPreferences(context).clear()
        HightouchPush.resetForTesting()
        analytics = mockAnalytics(context)
        HightouchPush.initialize(
            analytics,
            HightouchPushConfig.Builder("app-1")
                // Robolectric's fake host app has no android:icon and no manifest meta-data,
                // so without this the SDK's resolution chain returns 0 and skips posting.
                .setSmallIconResId(android.R.drawable.ic_dialog_info)
                .build(),
        )
    }

    @After
    fun tearDown() {
        HightouchPush.resetForTesting()
        PushPreferences(context).clear()
        nm.cancelAll()
    }

    @Test
    fun `handle returns false for non-Hightouch payloads`() {
        val msg = mockMessage(data = mapOf("other_provider" to """{"campaignId":1}"""))

        assertFalse(HightouchNotificationPresenter.handle(context, msg))

        assertEquals(0, Shadows.shadowOf(nm).allNotifications.size)
    }

    @Test
    fun `handle posts a notification on the default channel with title and body from data`() {
        val msg = mockMessage(
            data = mapOf(
                "hightouch" to """{"messageId":"m-1"}""",
                "title" to "Hello",
                "body" to "World",
            ),
        )

        val claimed = HightouchNotificationPresenter.handle(context, msg) { null }

        assertTrue(claimed)
        val posted = Shadows.shadowOf(nm).allNotifications
        assertEquals(1, posted.size)
        val notification = posted.single()
        assertEquals("Hello", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("World", notification.extras.getString(Notification.EXTRA_TEXT))
        // Default channel was created and used
        assertNotNull(nm.getNotificationChannel(HightouchNotificationPresenter.DEFAULT_CHANNEL_ID))
    }

    @Test
    fun `notification-block title and body are used when the data map omits them`() {
        // Hightouch pushes are data-only, but a message that arrives with an FCM notification block
        // must still render: effectiveData folds the block's title/body into the data map both the
        // inline render and the worker consume.
        val notification = mockk<RemoteMessage.Notification>(relaxed = true) {
            every { title } returns "NTitle"
            every { body } returns "NBody"
        }
        val msg = mockk<RemoteMessage>(relaxed = true).also {
            every { it.data } returns mapOf("hightouch" to """{"messageId":"m-notif"}""")
            every { it.notification } returns notification
        }

        HightouchNotificationPresenter.handle(context, msg) { null }

        val posted = Shadows.shadowOf(nm).allNotifications.single()
        assertEquals("NTitle", posted.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("NBody", posted.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `data map title and body override the notification block`() {
        // When a message carries both, the data map wins — effectiveData treats the notification
        // block as defaults only. (Logic-level guard; the API-21 putIfAbsent crash this code avoids
        // is a device-runtime issue Robolectric on the JVM cannot reproduce.)
        val notification = mockk<RemoteMessage.Notification>(relaxed = true) {
            every { title } returns "NTitle"
            every { body } returns "NBody"
        }
        val msg = mockk<RemoteMessage>(relaxed = true).also {
            every { it.data } returns mapOf(
                "hightouch" to """{"messageId":"m-both"}""",
                "title" to "DataTitle",
                "body" to "DataBody",
            )
            every { it.notification } returns notification
        }

        HightouchNotificationPresenter.handle(context, msg) { null }

        val posted = Shadows.shadowOf(nm).allNotifications.single()
        assertEquals("DataTitle", posted.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("DataBody", posted.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `handle uses configured channel id when set`() {
        HightouchPush.resetForTesting()
        HightouchPush.initialize(
            analytics,
            HightouchPushConfig.Builder("app-1")
                .setNotificationChannelId("custom_channel")
                .setSmallIconResId(android.R.drawable.ic_dialog_info)
                .build(),
        )
        val msg = mockMessage(
            data = mapOf(
                "hightouch" to """{"messageId":"m-2"}""",
                "title" to "T",
                "body" to "B",
            ),
        )

        HightouchNotificationPresenter.handle(context, msg) { null }

        assertNotNull(nm.getNotificationChannel("custom_channel"))
        assertNull(nm.getNotificationChannel(HightouchNotificationPresenter.DEFAULT_CHANNEL_ID))
    }

    @Test
    fun `handle attaches big picture style when image loader returns a bitmap`() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val msg = mockMessage(
            data = mapOf(
                "hightouch" to """{"messageId":"m-3","attachmentUrl":"https://x/i.png"}""",
                "title" to "Pic",
                "body" to "B",
            ),
        )

        val urlsRequested = mutableListOf<String>()
        // postNotification is the synchronous core the worker calls; image rendering is tested
        // here directly (handle() defers image messages to the worker — see the "defers"/"worker"
        // tests below).
        HightouchNotificationPresenter.postNotification(context, msg.data) { url ->
            urlsRequested += url
            bitmap
        }

        assertEquals(listOf("https://x/i.png"), urlsRequested)
        val notification = Shadows.shadowOf(nm).allNotifications.single()
        // BigPictureStyle sets EXTRA_PICTURE under the hood
        val picture = notification.extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)
        assertNotNull(picture)
    }

    @Test
    fun `handle posts notification without image when loader returns null`() {
        val msg = mockMessage(
            data = mapOf(
                "hightouch" to """{"messageId":"m-4","attachmentUrl":"https://x/i.png"}""",
                "title" to "Pic",
                "body" to "B",
            ),
        )

        val claimed = HightouchNotificationPresenter.postNotification(context, msg.data) { null }

        assertTrue(claimed)
        val notification = Shadows.shadowOf(nm).allNotifications.single()
        val picture = notification.extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)
        assertNull(picture)
    }

    @Test
    fun `handle defers image messages to the worker instead of loading inline`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        // Non-http scheme so the worker's RichMediaLoader bails instantly (no network in the test);
        // a non-null attachmentUrl is all that's needed to take the worker path.
        val msg = mockMessage(
            data = mapOf(
                "hightouch" to """{"messageId":"m-img","attachmentUrl":"ht-test://image"}""",
                "title" to "Pic",
                "body" to "B",
            ),
        )

        // The inline loader must never run — if handle posted inline, this would throw.
        val claimed = HightouchNotificationPresenter.handle(context, msg) { error("must not load inline") }

        assertTrue(claimed)
        val work = WorkManager.getInstance(context)
            .getWorkInfosByTag(HightouchNotificationWorker::class.java.name).get()
        assertEquals(1, work.size)
    }

    @Test
    fun `handle posts inline when there is no image`() {
        val msg = mockMessage(
            data = mapOf("hightouch" to """{"messageId":"m-inline"}""", "title" to "T", "body" to "B"),
        )

        HightouchNotificationPresenter.handle(context, msg) { null }

        assertEquals(1, Shadows.shadowOf(nm).allNotifications.size)
    }

    @Test
    fun `worker posts the notification from its input data`() {
        val worker = TestListenableWorkerBuilder<HightouchNotificationWorker>(context)
            .setInputData(
                workDataOf(
                    "hightouch" to """{"messageId":"m-worker"}""",
                    "title" to "Worker",
                    "body" to "Body",
                ),
            )
            .build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val notification = Shadows.shadowOf(nm).allNotifications.single()
        assertEquals("Worker", notification.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun `notification id is derived from messageId so duplicates replace prior posts`() {
        val msg = mockMessage(
            data = mapOf(
                "hightouch" to """{"messageId":"same-id"}""",
                "title" to "A",
                "body" to "1",
            ),
        )
        HightouchNotificationPresenter.handle(context, msg) { null }

        val msg2 = mockMessage(
            data = mapOf(
                "hightouch" to """{"messageId":"same-id"}""",
                "title" to "A",
                "body" to "2",
            ),
        )
        HightouchNotificationPresenter.handle(context, msg2) { null }

        val active = Shadows.shadowOf(nm).allNotifications
        assertEquals(1, active.size)
        assertEquals("2", active.single().extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `priority is default — display importance is owned by the channel`() {
        val msg = mockMessage(
            data = mapOf(
                "hightouch" to """{"messageId":"m-5"}""",
                "title" to "T",
                "body" to "B",
            ),
        )

        HightouchNotificationPresenter.handle(context, msg) { null }

        val notification = Shadows.shadowOf(nm).allNotifications.single()
        assertEquals(NotificationCompat.PRIORITY_DEFAULT, notification.priority)
    }

    @Test
    fun `default channel is created at IMPORTANCE_DEFAULT`() {
        val msg = mockMessage(
            data = mapOf("hightouch" to """{"messageId":"m"}""", "title" to "T", "body" to "B"),
        )

        HightouchNotificationPresenter.handle(context, msg) { null }

        val channel = nm.getNotificationChannel(HightouchNotificationPresenter.DEFAULT_CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test
    fun `per-message channel is honored when the host has registered it`() {
        nm.createNotificationChannel(
            NotificationChannel("orders", "Orders", NotificationManager.IMPORTANCE_HIGH),
        )
        val msg = mockMessage(
            data = mapOf(
                "hightouch" to """{"messageId":"m","notificationChannel":"orders"}""",
                "title" to "T",
                "body" to "B",
            ),
        )

        HightouchNotificationPresenter.handle(context, msg) { null }

        assertEquals("orders", Shadows.shadowOf(nm).allNotifications.single().channelId)
    }

    @Test
    fun `per-message channel falls back to default when not registered`() {
        val msg = mockMessage(
            data = mapOf(
                "hightouch" to """{"messageId":"m","notificationChannel":"ghost"}""",
                "title" to "T",
                "body" to "B",
            ),
        )

        HightouchNotificationPresenter.handle(context, msg) { null }

        assertEquals(
            HightouchNotificationPresenter.DEFAULT_CHANNEL_ID,
            Shadows.shadowOf(nm).allNotifications.single().channelId,
        )
        assertNull(nm.getNotificationChannel("ghost"))
    }

    @Test
    fun `group key is applied to the notification`() {
        val msg = mockMessage(
            data = mapOf(
                "hightouch" to """{"messageId":"m","groupKey":"promos"}""",
                "title" to "T",
                "body" to "B",
            ),
        )

        HightouchNotificationPresenter.handle(context, msg) { null }

        assertEquals("promos", Shadows.shadowOf(nm).allNotifications.single().group)
    }

    @Test
    fun `same notificationTag replaces a prior notification even with different messageIds`() {
        val tagged = """{"messageId":"%s","notificationTag":"order-1"}"""
        HightouchNotificationPresenter.handle(
            context,
            mockMessage(mapOf("hightouch" to tagged.format("a"), "title" to "T", "body" to "1")),
        ) { null }
        HightouchNotificationPresenter.handle(
            context,
            mockMessage(mapOf("hightouch" to tagged.format("b"), "title" to "T", "body" to "2")),
        ) { null }

        val active = Shadows.shadowOf(nm).allNotifications
        assertEquals(1, active.size)
        assertEquals("2", active.single().extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `sound field is ignored on API 26+ — the channel owns sound`() {
        val msg = mockMessage(
            data = mapOf(
                "hightouch" to """{"messageId":"m","sound":"chime.mp3"}""",
                "title" to "T",
                "body" to "B",
            ),
        )

        HightouchNotificationPresenter.handle(context, msg) { null }

        assertNull(Shadows.shadowOf(nm).allNotifications.single().sound)
    }

    @Test
    @Config(sdk = [25])
    fun `pre-O with an unbundled sound still posts using the default sound`() {
        val msg = mockMessage(
            data = mapOf(
                "hightouch" to """{"messageId":"m","sound":"not_bundled.mp3"}""",
                "title" to "T",
                "body" to "B",
            ),
        )

        val claimed = HightouchNotificationPresenter.handle(context, msg) { null }

        assertTrue(claimed)
        assertEquals(1, Shadows.shadowOf(nm).allNotifications.size)
    }

    private fun mockMessage(data: Map<String, String>): RemoteMessage =
        mockk<RemoteMessage>(relaxed = true).also {
            every { it.data } returns data
            every { it.notification } returns null
        }

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
