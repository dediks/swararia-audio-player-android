package com.swararia.audiolab

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopRemoteFadeOutTest {
    private val mapper = ObjectMapper()

    @Test
    fun capabilities_accepts_three_or_four_commands_and_rejects_unknown_or_overflow() {
        val legacy = DesktopRemoteJson.capabilities(mapper.readTree("""{"protocol":"v1","commands":["play","pause","stop"]}"""))
        assertEquals(setOf("play", "pause", "stop"), legacy)

        val withFade = DesktopRemoteJson.capabilities(mapper.readTree("""{"protocol":"v1","commands":["play","pause","stop","fade_out"]}"""))
        assertEquals(setOf("play", "pause", "stop", "fade_out"), withFade)

        val unknown = runCatching {
            DesktopRemoteJson.capabilities(mapper.readTree("""{"protocol":"v1","commands":["play","fade_out","seek"]}"""))
        }
        assertTrue(unknown.isFailure)

        val duplicate = runCatching {
            DesktopRemoteJson.capabilities(mapper.readTree("""{"protocol":"v1","commands":["play","pause","stop","fade_out","fade_out"]}"""))
        }
        assertTrue(duplicate.isFailure)
    }

    @Test
    fun command_body_encodes_fade_out_and_ui_state_gates_correctly() {
        val encoded = DesktopRemoteJson.commandBody("fade_out", "fade-key-1234").decodeToString()
        val node = mapper.readTree(encoded)
        assertEquals("fade_out", node.get("action").asText())
        assertEquals("fade-key-1234", node.get("idempotency_key").asText())

        val playingPlayback = DesktopRemotePlayback(role = "control", online = true, title = "Track A", playing = true, transport = "PLAYING", time = 12.0, duration = 120.0)
        val ready = DesktopRemoteUiState(
            endpoint = "192.168.18.47:8767",
            phase = "active",
            role = "control",
            playback = playingPlayback,
            commands = setOf("play", "pause", "stop", "fade_out"),
            fresh = true,
        )
        assertTrue(ready.canFadeOut)

        assertFalse(ready.copy(commands = setOf("play", "pause", "stop")).canFadeOut)
        assertFalse(ready.copy(role = "view_only").canFadeOut)
        assertFalse(ready.copy(fresh = false).canFadeOut)
        assertFalse(ready.copy(busy = true).canFadeOut)
        assertFalse(ready.copy(outcome = "pending").canFadeOut)
        assertFalse(ready.copy(playback = playingPlayback.copy(playing = false, transport = "PAUSED")).canFadeOut)
        assertFalse(ready.copy(playback = playingPlayback.copy(transport = "FADING")).canFadeOut)
        assertFalse(ready.copy(playback = playingPlayback.copy(online = false)).canFadeOut)
    }
}

