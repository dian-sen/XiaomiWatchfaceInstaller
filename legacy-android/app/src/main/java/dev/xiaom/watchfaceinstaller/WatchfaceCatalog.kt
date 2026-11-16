package dev.xiaom.watchfaceinstaller

/** Scan data and optional ID-bound XML declarations; modified is epoch milliseconds.
 * Filtering itself never reads files, checks headers, hashes or copies.
 */
data class WatchfaceItem(val name: String, val path: String, val size: Long, val modified: Long,
    val metadata: WatchfaceMetadata? = null, val metadataWarnings: List<String> = emptyList())

enum class BinFilter(val label: String) {
    CANDIDATE("候选"), ALL("全部 BIN"), RESOURCE("资源 BIN")
}

object WatchfaceCatalog {
    /** A naming/path heuristic, NOT proof of a real, valid or compatible watchface. */
    fun isCandidate(item: WatchfaceItem): Boolean {
        val segments = item.path.split('/')
        return segments.last().equals("resource.bin", ignoreCase = true) &&
            segments.dropLast(1).none {
                it.equals("resources", ignoreCase = true) || it.equals("_lua", ignoreCase = true)
            }
    }

    /** RESOURCE is the complement of candidates, including unclassified/other BINs. */
    fun filter(files: List<WatchfaceItem>, mode: BinFilter, keyword: String): List<WatchfaceItem> {
        val query = keyword.trim()
        return files.filter { item ->
            val matchesMode = when (mode) {
                BinFilter.ALL -> true
                BinFilter.CANDIDATE -> isCandidate(item)
                BinFilter.RESOURCE -> !isCandidate(item)
            }
            matchesMode && (query.isEmpty() || item.name.contains(query, ignoreCase = true) ||
                item.path.contains(query, ignoreCase = true) || item.metadata?.name?.contains(query, ignoreCase = true) == true)
        }
    }
}
