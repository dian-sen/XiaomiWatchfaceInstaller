package dev.xiaom.watchfaceinstaller

import org.junit.Assert.*
import org.junit.Test

class WatchfaceCatalogTest {
    private fun item(path: String, name: String = path.substringAfterLast('/')) =
        WatchfaceItem(name, path, 42, 1790521266000)

    @Test fun resourceBinOutsideResourceDirectoriesIsOnlyACandidate() {
        assertTrue(WatchfaceCatalog.isCandidate(item("/cache/package/resource.bin")))
        assertTrue(WatchfaceCatalog.isCandidate(item("/cache/package/RESOURCE.BIN")))
        // Metadata-only rule still accepts a nonexistent file: it is not validation.
        assertTrue(WatchfaceCatalog.isCandidate(item("/nonexistent/resource.bin")))
    }

    @Test fun excludesResourceDirectoriesAtAnyDepthAndIgnoresCase() {
        for (path in listOf("/cache/resources/resource.bin", "/cache/resources/_lua/theme/1.bin",
            "/cache/_lua/theme/resource.bin", "/cache/RESOURCES/theme/RESOURCE.BIN")) {
            assertFalse(path, WatchfaceCatalog.isCandidate(item(path)))
        }
    }

    @Test fun comparesWholeSegmentsAndUsesRealPathNotDisplayName() {
        assertTrue(WatchfaceCatalog.isCandidate(item("/cache/my_resources/resource.bin")))
        assertTrue(WatchfaceCatalog.isCandidate(item("/cache/_lua_backup/resource.bin")))
        assertFalse(WatchfaceCatalog.isCandidate(item("/cache/foo.bin", "resource.bin")))
        assertFalse(WatchfaceCatalog.isCandidate(item("/cache/not-resource.bin")))
    }

    @Test fun modesPartitionAllEntriesWithoutReadingOrDroppingUnknownBins() {
        val files = listOf(item("/cache/a/resource.bin"), item("/cache/a/resources/_lua/1.bin"),
            item("/cache/b/random.bin"), item("/cache/b/resources/resource.bin"))
        assertEquals(listOf(files[0]), WatchfaceCatalog.filter(files, BinFilter.CANDIDATE, ""))
        assertEquals(files.drop(1), WatchfaceCatalog.filter(files, BinFilter.RESOURCE, ""))
        assertEquals(files, WatchfaceCatalog.filter(files, BinFilter.ALL, ""))
        assertEquals(4, files.size)
    }

    @Test fun keywordMatchesNameOrPathCaseInsensitivelyAndCombinesWithMode() {
        val candidate = item("/cache/PackageA/resource.bin", "天气")
        val resource = item("/cache/PackageA/resources/1.bin")
        val files = listOf(candidate, resource)
        assertEquals(files, WatchfaceCatalog.filter(files, BinFilter.ALL, "  PACKAGEa "))
        assertEquals(listOf(candidate), WatchfaceCatalog.filter(files, BinFilter.ALL, "天气"))
        assertEquals(listOf(resource), WatchfaceCatalog.filter(files, BinFilter.RESOURCE, "packageA"))
        assertTrue(WatchfaceCatalog.filter(files, BinFilter.CANDIDATE, "1.bin").isEmpty())
        assertTrue(WatchfaceCatalog.filter(files, BinFilter.ALL, "no match").isEmpty())
        assertEquals(files, WatchfaceCatalog.filter(files, BinFilter.ALL, "  "))
    }

    @Test fun emptyResultsAreSafeForEveryMode() {
        for (mode in BinFilter.entries) assertTrue(WatchfaceCatalog.filter(emptyList(), mode, "x").isEmpty())
    }
}
