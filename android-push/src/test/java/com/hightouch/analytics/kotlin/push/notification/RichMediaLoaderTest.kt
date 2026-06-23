package com.hightouch.analytics.kotlin.push.notification

import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RichMediaLoaderTest {

    @Test
    fun `rejects malformed URLs`() {
        assertNull(RichMediaLoader.load("not a url"))
    }

    @Test
    fun `rejects non-http schemes (ftp)`() {
        assertNull(RichMediaLoader.load("ftp://example.com/image.png"))
    }

    @Test
    fun `rejects file URLs to prevent accidental local read`() {
        assertNull(RichMediaLoader.load("file:///etc/passwd"))
    }
}
