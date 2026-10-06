package dev.librepocket.skill

import java.security.MessageDigest

/** SHA-256 雜湊小工具（安裝校驗用）。 */
object SkillHashes {
    fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    fun sha256Hex(text: String): String =
        sha256Hex(text.toByteArray(Charsets.UTF_8))
}
