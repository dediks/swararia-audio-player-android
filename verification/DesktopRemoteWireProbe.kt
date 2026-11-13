package com.swararia.audiolab

import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Host wire probe only. Reflection permits loopback fixtures without weakening production pairing validation. */
object DesktopRemoteWireProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val directory = Files.createTempDirectory("desktop-wire-")
        val metadata = directory.resolve("fixture.json")
        val fixture = ProcessBuilder(args[0], args[1], args[2], metadata.toString())
            .redirectError(directory.resolve("fixture-stderr.log").toFile()).start()
        val input = fixture.inputStream.bufferedReader()
        val output = fixture.outputStream.bufferedWriter()
        fun control(command: String): String {
            output.write(command); output.newLine(); output.flush()
            return input.readLine() ?: error("Fixture exited")
        }
        try {
            check(input.readLine() == "READY") { "Fixture startup failed" }
            val data = DesktopRemoteJson.objectValue(Files.readAllBytes(metadata))
            val endpoint = data["endpoint"].textValue()
            val pin = Base64.getUrlDecoder().decode(data["fingerprint"].textValue())
            val invite = data["invite"].textValue()
            fun pairing(host: String = endpoint, digest: ByteArray = pin): DesktopRemotePairing {
                val constructor = DesktopRemotePairing::class.java.getDeclaredConstructor(
                    String::class.java, ByteArray::class.java, DesktopRemoteSecret::class.java)
                constructor.isAccessible = true
                return constructor.newInstance(host, digest, DesktopRemoteSecret(invite))
            }
            suspend fun rejectedTrust(value: DesktopRemotePairing) {
                try { DesktopRemoteHttpsApi().pair(value); error("Invalid TLS identity accepted") }
                catch (failure: DesktopRemoteFailure) { check(failure.kind == DesktopRemoteFailure.Kind.TRUST) }
            }
            rejectedTrust(pairing(digest = pin.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }))
            rejectedTrust(pairing(host = "localhost:" + endpoint.substringAfter(':')))
            println("PASS wire TLS: wrong fingerprint and IP identity rejected before pairing")

            val api = DesktopRemoteHttpsApi()
            val connection = pairing()
            val grant = api.pair(connection)
            connection.forgetInvitation()
            var approval = api.session(connection, grant)
            repeat(20) {
                if (approval.status != "active") { delay(50); approval = api.session(connection, grant) }
            }
            check(approval.status == "active" && approval.role == "control")
            check(api.capabilities(connection, grant) == setOf("play", "pause", "stop", "fade_out", "prev", "next", "select_track", "select_group"))
            val catalog = api.tracks(connection, grant)
            check(catalog.online)
            val state = api.state(connection, grant)
            check(state.online && state.title == "Wire fixture.wav")
            val sent = api.command(connection, grant, "play", UUID.randomUUID().toString())
            check(sent.status == "accepted")
            var result = api.commandStatus(connection, grant, sent.id)
            repeat(20) {
                if (result.status != "rejected") { delay(50); result = api.commandStatus(connection, grant, sent.id) }
            }
            check(result.status == "rejected") { "Fixture has no audio and must never report applied" }
            println("PASS wire desktop: actual HTTPS pairing, approval, capabilities, state and accepted-to-rejected command")

            fun posts(): Int = DesktopRemoteJson.objectValue(control("STATS").toByteArray())["command_posts"].intValue()
            val before = posts()
            check(control("DROP_NEXT_COMMAND") == "OK")
            try { api.command(connection, grant, "pause", UUID.randomUUID().toString()); error("Dropped response accepted") }
            catch (_: DesktopRemoteFailure) { }
            check(posts() == before + 1) { "POST was replayed after uncertain transport failure" }
            println("PASS wire ambiguous response: exactly one POST, no automatic replay")

            check(control("SLOW_ON") == "OK")
            val deadlineFailure = withTimeoutOrNull(11_000) {
                try { api.state(connection, grant); false }
                catch (failure: DesktopRemoteFailure) { failure.kind == DesktopRemoteFailure.Kind.UNAVAILABLE }
            }
            check(deadlineFailure == true) { "Client lacks bounded total slow-drip deadline" }
            check(control("SLOW_OFF") == "OK")
            println("PASS wire slow-drip: client total deadline ends a continuously streaming response")

            check(control("SLOW_ON") == "OK")
            val reading = launch { try { api.state(connection, grant) } catch (_: DesktopRemoteFailure) { } }
            delay(300)
            withTimeout(6000) { reading.cancelAndJoin() }
            check(control("SLOW_OFF") == "OK")
            println("PASS wire cancellation: in-flight read closes and joins")

            check(control("ROLE_VIEW") == "OK")
            check(api.session(connection, grant).role == "view_only")
            try { api.command(connection, grant, "stop", UUID.randomUUID().toString()); error("View-only command accepted") }
            catch (failure: DesktopRemoteFailure) { check(failure.kind == DesktopRemoteFailure.Kind.AUTH) }
            check(control("REVOKE") == "OK")
            try { api.session(connection, grant); error("Revoked session accepted") }
            catch (failure: DesktopRemoteFailure) { check(failure.kind == DesktopRemoteFailure.Kind.AUTH) }
            println("PASS wire authorization: actual view-only and revoked backend identities fail closed")
        } finally {
            runCatching { output.write("STOP\n"); output.flush() }
            if (!fixture.waitFor(5, TimeUnit.SECONDS)) { fixture.destroyForcibly(); fixture.waitFor(5, TimeUnit.SECONDS) }
            input.close(); output.close()
            Files.deleteIfExists(metadata)
            Files.deleteIfExists(directory.resolve("fixture-stderr.log"))
            Files.deleteIfExists(directory)
        }
    }
}
