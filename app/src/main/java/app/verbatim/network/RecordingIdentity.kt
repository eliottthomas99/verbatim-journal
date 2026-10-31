package app.verbatim.network

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Same UUID v5 as Python uuid.uuid5(NAMESPACE_URL, "moe:filename:timestamp").
 * Known before sending, including when the response is lost or returns 503. */
internal fun serverRecordingId(filename: String, timestamp: Long): String {
    val namespace = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
    val bytes = MessageDigest.getInstance("SHA-1").run {
        update(ByteBuffer.allocate(16).putLong(namespace.mostSignificantBits)
            .putLong(namespace.leastSignificantBits).array())
        digest("moe:$filename:$timestamp".toByteArray(Charsets.UTF_8))
    }
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val buffer = ByteBuffer.wrap(bytes)
    return UUID(buffer.long, buffer.long).toString()
}
