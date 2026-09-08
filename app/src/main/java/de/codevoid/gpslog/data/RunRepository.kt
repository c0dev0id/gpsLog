package de.codevoid.gpslog.data

import java.io.File

/** Metadata for one run, derived from its file. Start/end come from the first/last record. */
data class RunInfo(
    val id: Long,
    val file: File,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val pointCount: Long,
)

/**
 * Owns the runs directory. Each run is one `<id>.dat` file where the id is the run's start time in
 * epoch millis. Listing, creating and deleting runs are plain filesystem operations; no database.
 */
class RunRepository(private val runsDir: File) {

    init {
        runsDir.mkdirs()
    }

    fun newRunFile(startMillis: Long): File = File(runsDir, "$startMillis.dat")

    fun runFile(id: Long): File = File(runsDir, "$id.dat")

    fun delete(id: Long): Boolean = runFile(id).delete()

    /** All runs, newest first. Files without a valid record are still listed at 0 points. */
    fun listRuns(): List<RunInfo> {
        val files = runsDir.listFiles { f -> f.isFile && f.name.endsWith(".dat") } ?: return emptyList()
        return files.mapNotNull { file ->
            val id = file.nameWithoutExtension.toLongOrNull() ?: return@mapNotNull null
            val count = RunReader.pointCount(file)
            val start = RunReader.firstRecord(file)?.timeMillis ?: id
            val end = RunReader.lastRecord(file)?.timeMillis ?: start
            RunInfo(id = id, file = file, startTimeMillis = start, endTimeMillis = end, pointCount = count)
        }.sortedByDescending { it.id }
    }
}
