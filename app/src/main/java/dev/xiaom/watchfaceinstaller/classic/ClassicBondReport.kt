// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.xiaom.watchfaceinstaller.classic

/** Read-only snapshot of Android's already cached bonded-device UUIDs. No MAC/name/keys. */
@ConsistentCopyVisibility
data class ClassicBondReport private constructor(
    val bonded: Boolean,
    val deviceType: String,
    val uuids: List<String>,
    val standardServices: Map<String, String>
) {
    fun diagnostic(): String = "bonded=$bonded type=$deviceType cachedUUIDCount=${uuids.size} " +
        "standard=${standardServices.entries.joinToString { "${it.key}:${it.value}" }}"

    fun copyText(): String = uuids.joinToString(separator = "\n")

    companion object {
        private val STANDARD = linkedMapOf(
            "00001101-0000-1000-8000-00805f9b34fb" to "SPP",
            "0000110c-0000-1000-8000-00805f9b34fb" to "AVRCP_TARGET",
            "0000110e-0000-1000-8000-00805f9b34fb" to "AVRCP",
            "0000110f-0000-1000-8000-00805f9b34fb" to "AVRCP_CONTROLLER",
            "0000111e-0000-1000-8000-00805f9b34fb" to "HFP",
            "0000111f-0000-1000-8000-00805f9b34fb" to "HFP_AUDIO_GATEWAY",
            "00001108-0000-1000-8000-00805f9b34fb" to "HSP"
        )
        fun from(bonded: Boolean, type: String, raw: Iterable<String>?): ClassicBondReport {
            val normalized = (raw ?: emptyList()).map { it.lowercase(java.util.Locale.ROOT) }
                .filter { it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) }
                .distinct().sorted().take(32)
            val standard = normalized.mapNotNull { uuid -> STANDARD[uuid]?.let { uuid to it } }.toMap()
            return ClassicBondReport(bonded, type.takeIf { it in setOf("CLASSIC", "LE", "DUAL") } ?: "UNKNOWN", normalized, standard)
        }
    }
}
