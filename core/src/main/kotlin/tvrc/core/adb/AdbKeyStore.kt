package tvrc.core.adb

import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * ADB RSA 私钥持久化（F-02）：必须存到 filesDir 且权限 600，否则每次重启都弹授权。
 * 同时生成 ADB 公钥 wire 格式（524 字节结构的 Base64 + 空格 + 名字 + NUL）。
 */
class AdbKeyStore(private val dir: File, private val name: String = "adbkey") {

    private val privateFile = File(dir, "$name.pk8")
    private val publicFile = File(dir, "$name.pub")

    val privateKeyPath: String get() = privateFile.absolutePath
    val publicKeyPath: String get() = publicFile.absolutePath

    fun exists(): Boolean = privateFile.isFile && publicFile.isFile

    fun loadOrCreate(): KeyPair {
        if (exists()) {
            runCatching { return load() }
            // 损坏则重新生成
        }
        return generateAndStore()
    }

    private fun generateAndStore(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp = gen.generateKeyPair()
        if (!dir.exists()) dir.mkdirs()
        privateFile.writeBytes(kp.private.encoded) // PKCS#8 DER
        publicFile.writeBytes(kp.public.encoded)   // X.509 DER
        restrict(privateFile)
        restrict(publicFile)
        return kp
    }

    private fun load(): KeyPair {
        val kf = KeyFactory.getInstance("RSA")
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes()))
        val pub = kf.generatePublic(X509EncodedKeySpec(publicFile.readBytes()))
        return KeyPair(pub, priv)
    }

    /** 只允许所有者读写（等价 chmod 600）。 */
    private fun restrict(f: File) {
        f.setReadable(false, false)
        f.setWritable(false, false)
        f.setExecutable(false, false)
        f.setReadable(true, true)
        f.setWritable(true, true)
    }

    companion object {
        const val BLOB_STRUCT_SIZE = 524

        /**
         * ADB 公钥 wire 格式：
         *   uint32 modulus_size_words(=64) | uint32 n0inv | uint8 n[256] LE | uint8 rr[256] LE | uint32 exponent LE
         * 此函数只返回 524 字节二进制结构，文本编码由 adbPublicKeyWire 完成。
         */
        fun adbPublicKeyBlob(pub: RSAPublicKey): ByteArray {
            val n = pub.modulus
            val e = pub.publicExponent
            val n0 = n.and(BigInteger("FFFFFFFF", 16)).toLong()
            val n0inv = BigInteger.valueOf(n0).modInverse(BigInteger.ONE.shiftLeft(32))
                .negate().and(BigInteger("FFFFFFFF", 16)).toLong()
            val rr = BigInteger.ONE.shiftLeft(4096).mod(n)

            val out = java.io.ByteArrayOutputStream()
            out.write(uint32(64))
            out.write(uint32(n0inv))
            out.write(fixedLittleEndian(n, 256))
            out.write(fixedLittleEndian(rr, 256))
            out.write(uint32(e.toLong()))
            return out.toByteArray()
        }

        fun adbPublicKeyBase64(pub: RSAPublicKey): String =
            Base64.getEncoder().encodeToString(adbPublicKeyBlob(pub))

        fun adbPublicKeyWire(pub: RSAPublicKey, name: String = "unknown@tvrc"): ByteArray =
            (adbPublicKeyBase64(pub) + " " + name + "\u0000").toByteArray(Charsets.US_ASCII)

        private fun uint32(value: Long): ByteArray = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )

        private fun fixedLittleEndian(value: BigInteger, size: Int): ByteArray {
            val be = value.toByteArray() // 有符号大端，可能带前导 0
            val out = ByteArray(size)
            var srcIndex = be.size - 1
            var dstIndex = 0
            while (srcIndex >= 0 && dstIndex < size) {
                out[dstIndex] = be[srcIndex]
                srcIndex--
                dstIndex++
            }
            return out
        }
    }
}
