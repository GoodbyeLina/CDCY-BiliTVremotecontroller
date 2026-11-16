package tvrc.core.adb

import java.io.IOException
import java.security.PrivateKey
import java.security.Signature

/** ADB AUTH 的签名：对设备下发的 token 做 SHA1withRSA（PKCS#1 v1.5），2048 位密钥定长 256 字节。 */
object AdbAuth {

    const val SIGNATURE_SIZE = 256

    fun signToken(privateKey: PrivateKey, token: ByteArray): ByteArray {
        val signer = Signature.getInstance("SHA1withRSA")
        signer.initSign(privateKey)
        signer.update(token)
        val raw = signer.sign()
        if (raw.size == SIGNATURE_SIZE) return raw
        if (raw.size > SIGNATURE_SIZE) throw IOException("unexpected signature size " + raw.size)
        val padded = ByteArray(SIGNATURE_SIZE)
        System.arraycopy(raw, 0, padded, SIGNATURE_SIZE - raw.size, raw.size)
        return padded
    }
}
