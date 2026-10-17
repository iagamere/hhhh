package com.abdo.ps4monitor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

/**
 * Everything the "PS4" screen shows. All of it is read through ezRemote Server (the /v2/fs routes and /v2/sysinfo), so it also works while a game is running.
 * NOTHING here is verified on a real console: the folder names below are the usual PS4 ones from public documentation, and the screen shows
 * a "Check paths" report so you can see exactly which of them the console answered.
 */
data class Capture(val path: String, val name: String, val size: Long, val mtime: Long, val video: Boolean, val titleId: String)
data class GameInfo(val titleId: String, val title: String, val version: String, val category: String, val iconPath: String?, val sfoPath: String?)
data class Volume(val label: String, val path: String, val total: Long, val free: Long)
data class PathCheck(val path: String, val ok: Boolean, val detail: String)
class CaptureScan(val items: List<Capture>, val notes: List<String>, val truncated: Boolean)

object Ps4Info {
    private val PHOTO = setOf("jpg", "jpeg", "png", "bmp", "webp")
    private val VIDEO = setOf("mp4", "webm", "mkv", "mov", "m4v", "avi")
    private val TITLE_ID = Regex("""^(CUSA|PPSA|NPXS|NPXX)\d{4,6}$""")
    /** title id -> title, filled when the games list was read (used to name captures). */
    @Volatile var names: Map<String, String> = emptyMap()
    const val CAPTURE_ROOT = "/user/av_contents"

    private fun ext(n: String) = n.substringAfterLast('.', "").lowercase()
    private fun child(dir: String, n: String) = if (dir.endsWith("/")) dir + n else "$dir/$n"

    /** Lists a folder through the server, mapping every failure to a short English sentence (Tx translates the common ones). */
    private fun listOrNote(ps4: Ps4, path: String, notes: MutableList<String>): List<FsEntry>? = try { EzServer.fsList(ps4, path, 8000) }
        catch (e: EzServer.FsUnavailable) { notes.add("$path: not allowed or not reachable (${e.message})"); null }
        catch (e: EzError) { notes.add("$path: ${e.message}"); null }
        catch (e: Exception) { notes.add("$path: ${e.message}"); null }

    /** Photos and videos under /user/av_contents (and the user's extra folders), newest first. At most [limit] files and [maxDirs] folders are visited. */
    fun scanCaptures(ps4: Ps4, limit: Int = 2000, maxDirs: Int = 400): CaptureScan {
        val notes = ArrayList<String>(); val out = ArrayList<Capture>()
        var dirs = 0; var truncated = false
        val all = (listOf(CAPTURE_ROOT) + EzServer.roExtra().filter { it.contains("av_contents") || it.contains("capture", true) }).distinct()
        val roots = all.filter { r -> all.none { o -> o != r && r.startsWith("$o/") } }
        val stack = ArrayDeque<Triple<String, Int, String>>()
        roots.forEach { stack.add(Triple(it, 0, "")) }
        while (stack.isNotEmpty()) {
            val (dir, depth, tid) = stack.removeLast()
            if (dirs >= maxDirs || out.size >= limit) { truncated = true; break }
            dirs++
            val l = listOrNote(ps4, dir, notes) ?: continue
            for (e in l) {
                val p = child(dir, e.name)
                if (e.isDir) { if (depth < 5) stack.add(Triple(p, depth + 1, if (TITLE_ID.matches(e.name)) e.name else tid)) }
                else {
                    val x = ext(e.name)
                    val video = x in VIDEO
                    if (x in PHOTO || video) { if (out.size < limit) out.add(Capture(p, e.name, e.size, e.mtime, video, tid)) else truncated = true }
                }
            }
        }
        return CaptureScan(out.distinctBy { it.path }.sortedByDescending { it.mtime }, notes, truncated)
    }

    // ---------------- games ----------------
    /** Minimal PARAM.SFO reader (key table + data table). Returns {} when the bytes are not an SFO. */
    fun parseSfo(b: ByteArray): Map<String, String> {
        fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        if (b.size < 20 || b[0].toInt() != 0 || b[1].toInt() != 'P'.code || b[2].toInt() != 'S'.code || b[3].toInt() != 'F'.code) return emptyMap()
        val keyOff = u32(8); val dataOff = u32(12); val n = u32(16)
        if (n < 0 || n > 512 || keyOff < 0 || dataOff < 0) return emptyMap()
        val m = LinkedHashMap<String, String>()
        for (i in 0 until n) {
            val e = 20 + i * 16
            if (e + 16 > b.size) break
            val kOff = keyOff + u16(e); val fmt = u16(e + 2); val len = u32(e + 4); val dOff = dataOff + u32(e + 12)
            if (kOff < 0 || kOff >= b.size || dOff < 0 || len < 0 || dOff.toLong() + len > b.size) continue
            var k = kOff; while (k < b.size && b[k].toInt() != 0) k++
            val key = String(b, kOff, k - kOff, Charsets.US_ASCII)
            if (fmt == 0x0404) m[key] = u32(dOff).toString()
            else { var l = 0; while (l < len && b[dOff + l].toInt() != 0) l++; m[key] = String(b, dOff, l, Charsets.UTF_8) }
        }
        return m
    }

    private val META_DIRS = listOf("/user/appmeta", "/system_data/priv/appmeta")
    private val GAME_DIRS = listOf("/user/app", "/user/appmeta")

    /** Installed titles: folder names of /user/app (or /user/appmeta), enriched from each title's param.sfo when the console lets us read it. */
    fun scanGames(ps4: Ps4, limit: Int = 300, progress: (Int, Int) -> Unit = { _, _ -> }): Pair<List<GameInfo>, List<String>> {
        val notes = ArrayList<String>(); var ids: List<String> = emptyList()
        for (d in GAME_DIRS) {
            val l = listOrNote(ps4, d, notes) ?: continue
            ids = l.filter { it.isDir && TITLE_ID.matches(it.name) }.map { it.name }
            if (ids.isNotEmpty()) break
        }
        if (ids.isEmpty()) return emptyList<GameInfo>() to notes
        val res = ArrayList<GameInfo>()
        ids.sorted().take(limit).forEachIndexed { i, id ->
            progress(i, ids.size.coerceAtMost(limit))
            var sfo: Map<String, String> = emptyMap(); var sfoPath: String? = null; var icon: String? = null
            for (m in META_DIRS) {
                val p = "$m/$id/param.sfo"
                val bytes = try { EzServer.fsRead(ps4, p, 0, 64 * 1024, 6000) } catch (e: Exception) { ByteArray(0) }
                if (bytes.isNotEmpty()) { val s = runCatching { parseSfo(bytes) }.getOrDefault(emptyMap()); if (s.isNotEmpty()) { sfo = s; sfoPath = p; icon = "$m/$id/icon0.png"; break } }
            }
            res.add(GameInfo(id, sfo["TITLE"] ?: id, sfo["APP_VER"] ?: sfo["VERSION"] ?: "", sfo["CATEGORY"] ?: "", icon, sfoPath))
        }
        progress(res.size, res.size)
        names = res.associate { it.titleId to it.title }
        return res to notes
    }
    fun categoryLabel(c: String) = when (c) {
        "gd" -> tr("Game", "لعبة"); "gp" -> tr("Update", "تحديث"); "ac" -> tr("Add-on", "محتوى إضافي"); "gde" -> tr("Game", "لعبة")
        "" -> ""; else -> c
    }

    // ---------------- storage ----------------
    /** Volumes the server can measure. Several folders can sit on one volume, so identical (total, free) pairs are shown once. */
    fun storage(ps4: Ps4): Pair<List<Volume>, List<String>> {
        val notes = ArrayList<String>(); val seen = HashSet<Pair<Long, Long>>(); val out = ArrayList<Volume>()
        val cands = ArrayList<Pair<String, String>>()
        cands.add(tr("Data (/data)", "البيانات (/data)") to "/data")
        cands.add(tr("User partition", "قسم المستخدم") to CAPTURE_ROOT)
        for (i in 0..7) cands.add(tr("USB $i", "USB $i") to "/mnt/usb$i")
        cands.add(tr("Extended storage", "التخزين الموسّع") to "/mnt/ext0")
        for ((label, path) in cands) {
            try {
                val (t, f) = EzServer.fsSpace(ps4, path, 5000)
                if (t > 0 && seen.add(t to f)) out.add(Volume(label, path, t, f))
            } catch (e: EzServer.FsUnavailable) { if (!path.startsWith("/mnt/usb") && !path.startsWith("/mnt/ext")) notes.add("$path: not allowed or not reachable (${e.message})") }
            catch (e: EzError) { if (!path.startsWith("/mnt/usb") && !path.startsWith("/mnt/ext")) notes.add("$path: ${e.message}") }
            catch (e: Exception) { notes.add("$path: ${e.message}") }
        }
        return out to notes
    }

    // ---------------- diagnostics ----------------
    fun checkPaths(ps4: Ps4): List<PathCheck> {
        val paths = (EzServer.RO_DEFAULT + EzServer.roExtra() + EzServer.fsRootsKnown(ps4) + listOf(CAPTURE_ROOT + "/photo", CAPTURE_ROOT + "/video")).distinct()
        return paths.map { p ->
            try { val l = EzServer.fsList(ps4, p, 6000); PathCheck(p, true, "${l.count { it.isDir }} folders, ${l.count { !it.isDir }} files") }
            catch (e: EzServer.FsUnavailable) { PathCheck(p, false, "not allowed or not reachable (${e.message})") }
            catch (e: EzError) { PathCheck(p, false, e.message ?: "error") }
            catch (e: Exception) { PathCheck(p, false, e.message ?: "error") }
        }
    }

    /** Device facts: label -> value, from the console's own sysctl. Unknown names are simply missing; nothing is guessed. */
    fun deviceRows(j: JSONObject?): Pair<List<Pair<String, String>>, List<String>> {
        if (j == null) return emptyList<Pair<String, String>>() to emptyList()
        val v = j.optJSONObject("values") ?: JSONObject()
        val rows = ArrayList<Pair<String, String>>()
        fun s(k: String) = if (v.has(k)) v.optString(k) else null
        s("kern.ostype")?.let { rows.add(tr("System", "النظام") to it) }
        s("kern.osrelease")?.let { rows.add(tr("System release", "إصدار النظام") to it) }
        s("kern.version")?.let { rows.add(tr("Kernel version", "إصدار النواة") to it.lines().first().take(120)) }
        s("hw.model")?.let { rows.add(tr("CPU model", "المعالج") to it) }
        s("hw.machine")?.let { rows.add(tr("Architecture", "المعمارية") to it) }
        if (v.has("hw.ncpu")) rows.add(tr("CPU cores", "أنوية المعالج") to v.optLong("hw.ncpu").toString())
        if (v.has("hw.physmem")) rows.add(tr("Memory seen by the system", "الذاكرة التي يراها النظام") to Fmt.bytes(v.optLong("hw.physmem")))
        if (v.has("uptime_seconds")) rows.add(tr("Up for", "يعمل منذ") to Fmt.dur(v.optLong("uptime_seconds")))
        val miss = j.optJSONArray("missing")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        return rows to miss
    }
}

/** Decoded thumbnails, shared by the grid and the viewer. At most 3 reads at a time so the PS4's server is not flooded. */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 10).toInt().coerceAtLeast(4 shl 20)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val gate = Semaphore(3)
    const val MAX_READ = 8L shl 20
    fun cached(key: String): Bitmap? = cache.get(key)
    fun decode(b: ByteArray, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
    }
    /** Null when the file is too big, not an image, or could not be read. */
    suspend fun load(ps4: Ps4, path: String, size: Long, maxSide: Int): Bitmap? {
        val key = "${ps4.id}|$path|$maxSide"
        cache.get(key)?.let { return it }
        return gate.withPermit {
            cache.get(key) ?: try {
                val sz = if (size > 0) size else EzServer.fsSize(ps4, path, 6000)
                if (sz <= 0 || sz > MAX_READ) null
                else decode(EzServer.fsRead(ps4, path, 0, sz.toInt(), 20_000), maxSide)?.also { cache.put(key, it) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        }
    }
}
