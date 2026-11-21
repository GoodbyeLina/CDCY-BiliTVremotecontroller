package tvrc.core.adb

import java.io.IOException
import java.security.PrivateKey
import java.security.Signature

/** ADB token 已是 20 字节摘要；只做 SHA-1 DigestInfo + PKCS#1 v1.5 签名，不再次哈希。 */
object AdbAuth {

    const val SIGNATURE_SIZE = 256

    fun signToken(privateKey: PrivateKey, token: ByteArray): ByteArray {
        require(token.size == 20) { "ADB AUTH token must be 20 bytes" }
        val signer = Signature.getInstance("NONEwithRSA")
        signer.initSign(privateKey)
        signer.update(byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14))
        signer.update(token)
        val raw = signer.sign()
        if (raw.size == SIGNATURE_SIZE) return raw
        if (raw.size > SIGNATURE_SIZE) throw IOException("unexpected signature size " + raw.size)
        val padded = ByteArray(SIGNATURE_SIZE)
        System.arraycopy(raw, 0, padded, SIGNATURE_SIZE - raw.size, raw.size)
        return padded
    }
}
