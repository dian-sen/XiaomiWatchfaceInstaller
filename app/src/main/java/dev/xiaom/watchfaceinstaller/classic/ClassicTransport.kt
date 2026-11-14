// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.xiaom.watchfaceinstaller.classic

import java.io.Closeable

/** Ordered RFCOMM byte stream, not packets. read may split or coalesce arbitrarily.
 * close MUST interrupt connect/read/write. No retry, pairing, or reconnect inside transports. */
interface ClassicTransport : Closeable {
    suspend fun connect(mac: String)
    suspend fun read(): ByteArray
    suspend fun write(bytes: ByteArray)
}
