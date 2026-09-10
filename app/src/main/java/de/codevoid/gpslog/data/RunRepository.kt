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

    /**
     * Combines several runs into one. All points are read, merged in time order and written to a
     * temp file that then replaces the earliest run's file; the other originals are deleted. The
     * temp-then-rename keeps the originals intact until the merged file is complete and fsync'd, so
     * a crash mid-merge loses nothing. Returns the id of the merged run, or null if fewer than two
     * runs actually held data. Runs the same [RunWriter]/[RunReader] codec path as normal logging.
     */
    fun merge(ids: List<Long>): Long? {
        val files = ids.map { runFile(it) }.filter { it.exists() }
        if (files.size < 2) return null
        val combined = files.flatMap { RunReader.readAll(it) }.sortedBy { it.timeMillis }
        if (combined.isEmpty()) return null
        val newId = ids.min()
        val tmp = File(runsDir, "$newId.dat.tmp")
        tmp.delete()
        val writer = RunWriter(tmp)
        combined.forEach { writer.append(it) }
        writer.close()
        files.forEach { it.delete() }
        tmp.renameTo(runFile(newId))
        return newId
    }

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
