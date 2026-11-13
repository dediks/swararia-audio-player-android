package com.swararia.audiolab

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopRemoteFadeOutTest {
    private val mapper = ObjectMapper()

    @Test
    fun capabilities_accepts_three_four_or_seven_commands_and_rejects_unknown_or_overflow() {
        val legacy = DesktopRemoteJson.capabilities(mapper.readTree("""{"protocol":"v1","commands":["play","pause","stop"]}"""))
        assertEquals(setOf("play", "pause", "stop"), legacy)

        val withFade = DesktopRemoteJson.capabilities(mapper.readTree("""{"protocol":"v1","commands":["play","pause","stop","fade_out"]}"""))
        assertEquals(setOf("play", "pause", "stop", "fade_out"), withFade)

        val withTracks = DesktopRemoteJson.capabilities(mapper.readTree(
            """{"protocol":"v1","commands":["play","pause","stop","fade_out","prev","next","select_track"]}""",
        ))
        assertEquals(setOf("play", "pause", "stop", "fade_out", "prev", "next", "select_track"), withTracks)

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
    fun tracks_catalog_parses_strictly_and_rejects_path_or_url_leaks() {
        val parsed = DesktopRemoteJson.tracks(mapper.readTree("""
            {
              "protocol":"v1",
              "master_online":true,
              "catalog_version":3,
              "playlist":"Resepsi",
              "current_track_id":"trk_02",
              "tracks":[
                {"id":"trk_01","title":"01 - Opening.wav"},
                {"id":"trk_02","title":"02 - Masuk.mp3"}
              ]
            }
        """.trimIndent()))
        assertTrue(parsed.online)
        assertEquals(3, parsed.version)
        assertEquals("Resepsi", parsed.playlist)
        assertEquals("trk_02", parsed.currentTrackId)
        assertEquals(listOf(DesktopRemoteTrack("trk_01", "01 - Opening.wav"), DesktopRemoteTrack("trk_02", "02 - Masuk.mp3")), parsed.tracks)

        val pathLeak = runCatching {
            DesktopRemoteJson.tracks(mapper.readTree("""{"protocol":"v1","master_online":true,"catalog_version":1,"tracks":[{"id":"trk_01","title":"C:\\Music\\song.mp3"}]}"""))
        }
        assertTrue(pathLeak.isFailure)

        val dupId = runCatching {
            DesktopRemoteJson.tracks(mapper.readTree("""{"protocol":"v1","master_online":true,"catalog_version":1,"tracks":[{"id":"trk_01","title":"A.mp3"},{"id":"trk_01","title":"B.mp3"}]}"""))
        }
        assertTrue(dupId.isFailure)
    }

    @Test
    fun command_body_encodes_fade_out_and_select_track_and_ui_state_gates_correctly() {
        val fadeNode = mapper.readTree(DesktopRemoteJson.commandBody("fade_out", "fade-key-1234").decodeToString())
        assertEquals("fade_out", fadeNode.get("action").asText())
        assertEquals("fade-key-1234", fadeNode.get("idempotency_key").asText())

        val selectNode = mapper.readTree(
            DesktopRemoteJson.commandBody("select_track", "sel-key-1234", trackId = "trk_02", autoPlay = true).decodeToString(),
        )
        assertEquals("select_track", selectNode.get("action").asText())
        assertEquals("sel-key-1234", selectNode.get("idempotency_key").asText())
        assertEquals("trk_02", selectNode.get("track_id").asText())
        assertTrue(selectNode.get("auto_play").asBoolean())

        assertTrue(runCatching { DesktopRemoteJson.commandBody("play", "play-key-1234", trackId = "trk_01") }.isFailure)
        assertTrue(runCatching { DesktopRemoteJson.commandBody("select_track", "sel-key-1234", trackId = null) }.isFailure)

        val playingPlayback = DesktopRemotePlayback(role = "control", online = true, title = "Track A", playing = true, transport = "PLAYING", time = 12.0, duration = 120.0)
        val ready = DesktopRemoteUiState(
            endpoint = "192.168.18.47:8767",
            phase = "active",
            role = "control",
            playback = playingPlayback,
            commands = setOf("play", "pause", "stop", "fade_out", "prev", "next", "select_track"),
            tracks = listOf(DesktopRemoteTrack("trk_01", "Track A")),
            fresh = true,
        )
        assertTrue(ready.canFadeOut)
        assertTrue(ready.canSelectTrack)
        assertTrue(ready.canStepTrack)

        assertFalse(ready.copy(commands = setOf("play", "pause", "stop")).canFadeOut)
        assertFalse(ready.copy(commands = setOf("play", "pause", "stop", "fade_out")).canSelectTrack)
        assertFalse(ready.copy(role = "view_only").canFadeOut)
        assertFalse(ready.copy(role = "view_only").canSelectTrack)
        assertFalse(ready.copy(fresh = false).canFadeOut)
        assertFalse(ready.copy(busy = true).canFadeOut)
        assertFalse(ready.copy(outcome = "pending").canFadeOut)
        assertFalse(ready.copy(playback = playingPlayback.copy(playing = false, transport = "PAUSED")).canFadeOut)
        assertFalse(ready.copy(playback = playingPlayback.copy(transport = "FADING")).canFadeOut)
        assertFalse(ready.copy(playback = playingPlayback.copy(online = false)).canFadeOut)
        assertFalse(ready.copy(playback = playingPlayback.copy(title = null)).canStepTrack)
    }
}
