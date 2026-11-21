package tvrc.core.test

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvrc.core.adb.AdbKeyStore
import tvrc.core.adb.AdbPacket
import tvrc.core.adb.AdbTimeoutException
import tvrc.core.adb.DadbClient
import java.nio.file.Files
import javax.crypto.Cipher
import java.util.Base64

/**
 * DadbClient 的端到端协议测试：真实 TCP + 进程内假设备。
 * 不需要手机、不需要电视、不需要 Android —— 验的是「我们这一侧的 ADB 协议实现」。
 */
class DadbClientTest {

    private val dir = Files.createTempDirectory("adbkey-test").toFile()
    private val store = AdbKeyStore(dir)
    private val keyPair = store.loadOrCreate()

    @AfterEach
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun handshake(conn: FakeAdbConn) {
        val cnxn = conn.read()
        assertEquals(AdbPacket.A_CNXN, cnxn.command)
        assertEquals(AdbPacket.A_VERSION, cnxn.arg0)
        assertTrue(String(cnxn.payload, Charsets.UTF_8).startsWith("host::"))
        conn.write(
            AdbPacket(
                AdbPacket.A_CNXN, AdbPacket.A_VERSION, AdbPacket.MAX_DATA,
                "device::fake\u0000".toByteArray(Charsets.UTF_8)
            )
        )
    }

    @Test
    fun connectWithoutAuth() {
        val server = FakeAdbServer { conn -> handshake(conn) }.start()
        try {
            val client = DadbClient("127.0.0.1", server.port, store, connectTimeoutMs = 3000)
            assertTrue(client.connect(), "握手成功应为 true")
            client.close()
        } finally {
            server.stopAndCheck()
        }
    }

    @Test
    fun authSignatureIsVerifiableByDeviceToken() {
        val token = ByteArray(20) { (it + 1).toByte() }
        val server = FakeAdbServer { conn ->
            val cnxn = conn.read()
            assertEquals(AdbPacket.A_CNXN, cnxn.command)
            conn.write(AdbPacket(AdbPacket.A_AUTH, AdbPacket.AUTH_TOKEN, 0, token))

            val auth = conn.read()
            assertEquals(AdbPacket.A_AUTH, auth.command)
            assertEquals(AdbPacket.AUTH_SIGNATURE, auth.arg0)
            assertEquals(256, auth.payload.size, "2048 位 RSA 签名应为 256 字节")

            // AOSP RSA_verify(NID_sha1, token): recover the padded digest without hashing the token.
            val verifier = Cipher.getInstance("RSA/ECB/NoPadding")
            verifier.init(Cipher.DECRYPT_MODE, keyPair.public)
            val encoded = verifier.doFinal(auth.payload)
            val digestInfo = "3021300906052b0e03021a05000414".chunked(2).map { it.toInt(16).toByte() }.toByteArray() + token
            assertEquals(0, encoded[0].toInt())
            assertEquals(1, encoded[1].toInt())
            assertTrue(encoded.copyOfRange(2, 220).all { it == 0xff.toByte() })
            assertEquals(0, encoded[220].toInt())
            assertArrayEquals(digestInfo, encoded.copyOfRange(221, 256))

            conn.write(AdbPacket(AdbPacket.A_CNXN, AdbPacket.A_VERSION, AdbPacket.MAX_DATA, ByteArray(0)))
        }.start()
        try {
            val client = DadbClient("127.0.0.1", server.port, store, connectTimeoutMs = 3000)
            assertTrue(client.connect())
            client.close()
        } finally {
            server.stopAndCheck()
        }
    }

    @Test
    fun secondTokenRoundFallsBackToPublicKey() {
        val token = ByteArray(20) { 7 }
        val server = FakeAdbServer { conn ->
            conn.read()
            conn.write(AdbPacket(AdbPacket.A_AUTH, AdbPacket.AUTH_TOKEN, 0, token))
            val signature = conn.read()
            assertEquals(AdbPacket.AUTH_SIGNATURE, signature.arg0)

            conn.write(AdbPacket(AdbPacket.A_AUTH, AdbPacket.AUTH_TOKEN, 0, token))
            val pub = conn.read()
            assertEquals(AdbPacket.A_AUTH, pub.command)
            assertEquals(AdbPacket.AUTH_RSAPUBLICKEY, pub.arg0)
            val text = String(pub.payload, Charsets.US_ASCII)
            assertTrue(text.endsWith(" unknown@tvrc\u0000"))
            val decoded = Base64.getDecoder().decode(text.substringBefore(' '))
            assertEquals(524, decoded.size)
            assertEquals(64, decoded[0].toInt())

            conn.write(AdbPacket(AdbPacket.A_CNXN, AdbPacket.A_VERSION, AdbPacket.MAX_DATA, ByteArray(0)))
        }.start()
        try {
            val client = DadbClient("127.0.0.1", server.port, store, connectTimeoutMs = 3000)
            assertTrue(client.connect())
            client.close()
        } finally {
            server.stopAndCheck()
        }
    }

    @Test
    fun shellCollectsWritePayloadUntilClose() {
        val server = FakeAdbServer { conn ->
            handshake(conn)
            val open = conn.read()
            assertEquals(AdbPacket.A_OPEN, open.command)
            assertEquals("shell:echo hi\u0000", String(open.payload, Charsets.UTF_8))

            conn.write(AdbPacket(AdbPacket.A_OKAY, 5, open.arg0, ByteArray(0)))
            conn.write(AdbPacket(AdbPacket.A_WRTE, 5, open.arg0, "hi\n".toByteArray(Charsets.UTF_8)))
            assertEquals(AdbPacket.A_OKAY, conn.read().command) // 客户端对 WRTE 的确认
            conn.write(AdbPacket(AdbPacket.A_CLSE, 5, open.arg0, ByteArray(0)))
        }.start()
        try {
            val client = DadbClient("127.0.0.1", server.port, store, connectTimeoutMs = 3000)
            assertTrue(client.connect())
            val result = client.shell("echo hi", 3000)
            assertEquals(0, result.exitCode)
            assertEquals("hi\n", result.stdout)
            client.close()
        } finally {
            server.stopAndCheck()
        }
    }

    @Test
    fun shellWithExitCodeParsesMarkerLine() {
        val server = FakeAdbServer { conn ->
            handshake(conn)
            val open = conn.read()
            val body = "out\n__TVRC_EXIT__3\n"
            conn.write(AdbPacket(AdbPacket.A_OKAY, 9, open.arg0, ByteArray(0)))
            conn.write(AdbPacket(AdbPacket.A_WRTE, 9, open.arg0, body.toByteArray(Charsets.UTF_8)))
            conn.read()
            conn.write(AdbPacket(AdbPacket.A_CLSE, 9, open.arg0, ByteArray(0)))
        }.start()
        try {
            val client = DadbClient("127.0.0.1", server.port, store, connectTimeoutMs = 3000)
            assertTrue(client.connect())
            val result = client.shellWithExitCode("false", 3000)
            assertEquals(3, result.exitCode)
            assertEquals("out", result.stdout)
            client.close()
        } finally {
            server.stopAndCheck()
        }
    }

    @Test
    fun shellTimeoutRaisesAdbTimeoutException() {
        val server = FakeAdbServer { conn ->
            handshake(conn)
            conn.read() // OPEN
            Thread.sleep(800) // 故意不回应
        }.start()
        try {
            val client = DadbClient("127.0.0.1", server.port, store, connectTimeoutMs = 3000)
            assertTrue(client.connect())
            assertThrows(AdbTimeoutException::class.java) { client.shell("sleep 10", 200) }
            client.close()
        } finally {
            server.stopAndCheck()
        }
    }

    @Test
    fun shellBeforeConnectFails() {
        val client = DadbClient("127.0.0.1", 5555, store)
        assertThrows(java.io.IOException::class.java) { client.shell("echo hi", 1000) }
    }

    @Test
    fun keyIsPersistedAndReused() {
        val again = AdbKeyStore(dir).loadOrCreate()
        assertEquals(
            (keyPair.public as java.security.interfaces.RSAPublicKey).modulus,
            (again.public as java.security.interfaces.RSAPublicKey).modulus
        )
        assertTrue(dir.listFiles()!!.any { it.name.endsWith(".pk8") })
    }
}
