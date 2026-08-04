package me.rerere.rikkahub.data.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Test

// 回归测试 #44：WebDAV 路径段 percent-encode
// 此前直接拼接 → 空格 400、# 截断 URL、? 变 query、中文/& 非法 → 同步失败
class WebDavPathEncodingTest {

    @Test
    fun `space is encoded as percent20 not plus`() {
        assertEquals("my%20file.txt", encodePathSegment("my file.txt"))
    }

    @Test
    fun `hash is encoded`() {
        assertEquals("a%23b", encodePathSegment("a#b"))
    }

    @Test
    fun `question mark is encoded`() {
        assertEquals("a%3Fb", encodePathSegment("a?b"))
    }

    @Test
    fun `ampersand is encoded`() {
        assertEquals("a%26b", encodePathSegment("a&b"))
    }

    @Test
    fun `chinese characters are encoded`() {
        assertEquals("%E6%B5%8B%E8%AF%95.txt", encodePathSegment("测试.txt"))
    }

    @Test
    fun `slash inside a segment is encoded`() {
        assertEquals("a%2Fb", encodePathSegment("a/b"))
    }

    @Test
    fun `plain ascii passes through unchanged`() {
        assertEquals("plain-file.txt", encodePathSegment("plain-file.txt"))
    }
}
