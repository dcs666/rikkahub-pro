package me.rerere.ai.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildEndpointTest {

    @Test
    fun `normal base url appends path`() {
        assertEquals(
            "https://opencode.ai/zen/go/v1/responses",
            buildEndpoint("https://opencode.ai/zen/go/v1", "/responses")
        )
    }

    @Test
    fun `base url with trailing slash`() {
        assertEquals(
            "https://opencode.ai/zen/go/v1/responses",
            buildEndpoint("https://opencode.ai/zen/go/v1/", "/responses")
        )
    }

    @Test
    fun `base url already ends with responses path does not duplicate`() {
        assertEquals(
            "https://opencode.ai/zen/go/v1/responses",
            buildEndpoint("https://opencode.ai/zen/go/v1/responses", "/responses")
        )
    }

    @Test
    fun `base url ends with responses trailing slash does not duplicate`() {
        assertEquals(
            "https://opencode.ai/zen/go/v1/responses",
            buildEndpoint("https://opencode.ai/zen/go/v1/responses/", "/responses")
        )
    }

    @Test
    fun `chat completions path not duplicated`() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            buildEndpoint("https://api.example.com/v1", "/chat/completions")
        )
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            buildEndpoint("https://api.example.com/v1/chat/completions", "/chat/completions")
        )
    }

    @Test
    fun `empty path returns base`() {
        assertEquals(
            "https://api.example.com/v1",
            buildEndpoint("https://api.example.com/v1/", "")
        )
    }

    @Test
    fun `path without leading slash works`() {
        assertEquals(
            "https://api.example.com/v1/credits",
            buildEndpoint("https://api.example.com/v1", "credits")
        )
    }
}
