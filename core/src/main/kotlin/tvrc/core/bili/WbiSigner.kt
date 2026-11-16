package tvrc.core.bili

import tvrc.core.json.Json
import tvrc.core.json.JsonValue
import tvrc.core.json.field
import tvrc.core.json.strOrNull

/**
 * wbi 签名（L P2-1 / F-15）。算法来自 B 站 web 端：
 * 1. mixinKey = 用固定重排表 MIXIN_KEY_ENC_TAB 打乱 (imgKey + subKey)，取前 32 位；
 * 2. 所有参数值先过滤 !'()* 五个字符；
 * 3. 按 key 升序拼接 k=v&...，末尾追加 &wts=<秒级时间戳>；
 * 4. w_rid = md5(query + mixinKey)。
 */
object WbiSigner {

    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    )

    /** 需要从参数值里剔除的字符（F-15 的坑：不过滤会稳定 -403/校验失败）。 */
    private val ILLEGAL = Regex("[!'()*]")

    fun mixinKey(imgKey: String, subKey: String): String {
        val origin = imgKey + subKey
        val sb = StringBuilder(32)
        for (i in 0 until 32) {
            val idx = MIXIN_KEY_ENC_TAB[i]
            if (idx < origin.length) sb.append(origin[idx])
        }
        return sb.toString()
    }

    fun filterValue(value: String): String = ILLEGAL.replace(value, "")

    /** 返回最终 query（含 wts，不含 w_rid）与 w_rid 本身。 */
    fun sign(params: Map<String, Any?>, mixinKey: String, wts: Long): SignedQuery {
        val pairs = params.entries
            .filter { it.value != null }
            .sortedBy { it.key }
            .map { (k, v) -> encode(k) + "=" + encode(filterValue(v.toString())) }
            .toMutableList()
        pairs.add("wts=" + wts)
        val query = pairs.joinToString("&")
        return SignedQuery(query, md5Hex(query + mixinKey))
    }

    /** 直接给出可放进 URL 的参数表（含 wts 与 w_rid）。 */
    fun signedParams(params: Map<String, Any?>, mixinKey: String, wts: Long): Map<String, String> {
        val signed = sign(params, mixinKey, wts)
        val result = LinkedHashMap<String, String>()
        params.forEach { (k, v) -> if (v != null) result[k] = filterValue(v.toString()) }
        result["wts"] = wts.toString()
        result["w_rid"] = signed.wRid
        return result
    }

    /** encodeURIComponent 等价：仅保留 A-Za-z0-9-_.~。 */
    fun encode(raw: String): String {
        val sb = StringBuilder()
        for (b in raw.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                sb.append(ch)
            } else {
                sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
            }
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    private fun md5Hex(text: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(32)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString().lowercase()
    }
}

data class SignedQuery(val query: String, val wRid: String)

data class WbiKeys(val imgKey: String, val subKey: String) {
    val mixinKey: String get() = WbiSigner.mixinKey(imgKey, subKey)
}

/** 从 nav 接口响应里取 wbi_img.img_url / sub_url 的文件名（去掉扩展名）。 */
object WbiKeysParser {
    fun parse(navJson: String): WbiKeys? {
        val root = runCatching { Json.parse(navJson) }.getOrNull() as? JsonValue.Obj ?: return null
        val data = root.field("data") as? JsonValue.Obj ?: return null
        val wbi = data.field("wbi_img") as? JsonValue.Obj ?: return null
        val img = wbi.field("img_url").strOrNull() ?: return null
        val sub = wbi.field("sub_url").strOrNull() ?: return null
        return WbiKeys(basename(img), basename(sub))
    }

    private fun basename(url: String): String =
        url.substringAfterLast('/').substringBeforeLast('.')
}

/** buvid3 / buvid4（L P2-1：直播/播放接口要带这个 cookie，否则容易 -352）。 */
data class Buvid(val buvid3: String, val buvid4: String)

object BuvidParser {
    fun parse(spiJson: String): Buvid? {
        val root = runCatching { Json.parse(spiJson) }.getOrNull() as? JsonValue.Obj ?: return null
        val data = root.field("data") as? JsonValue.Obj ?: return null
        val b3 = data.field("b_3").strOrNull() ?: return null
        val b4 = data.field("b_4").strOrNull() ?: return null
        return Buvid(b3, b4)
    }
}
