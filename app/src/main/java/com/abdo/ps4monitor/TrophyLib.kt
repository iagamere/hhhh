package com.abdo.ps4monitor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A console account: its folder id (8 hex digits under /user/home) and the name from username.dat. */
data class Account(val id: String, val name: String, val avatarPaths: List<String>)
/** One trophy of a game. [grade] is P (platinum), G, S, B, or ? when the trophy file does not say. */
data class Trophy(val id: Int, val name: String, val detail: String, val grade: Char, val hidden: Boolean, val icon: String?)
/** Trophy list read from the game's own trophy pack (trp file); [files] maps entry name -> (offset, size) for the pictures. */
data class TrophyPack(val npwr: String, val title: String, val trophies: List<Trophy>, val trpPath: String, val files: Map<String, Pair<Long, Long>>, val namesReadable: Boolean)
/** [unlocked]: trophy id -> time (seconds, 0 when unknown); null = the console's unlock data could not be read. */
class TrophyState(val unlocked: Map<Int, Long>?, val notes: List<String>)

/**
 * Trophy reader. What is VERIFIED from public documentation and what is a GUESS:
 *  - the game's trophy pack (the .trp files in sce_sys/trophy, big-endian table of files) and npbind.dat holding the NPWR id: documented layout.
 *  - the trophy names are plain XML (TROP.SFM / TROPCONF.SFM) only in some packs; if they are encrypted the list shows numbered trophies with their pictures.
 *  - WHERE the console keeps each account's unlocked trophies is NOT verified: the reader searches the account's trophy folder for SQLite files and
 *    matches tables by column names. If nothing matches the screen says so and lists what it found (use Device > Scan trophies and send the result).
 */
object TrophyLib {
    private val NPWR = Regex("NPWR\\d{5}_\\d{2}")
    private val HEX8 = Regex("^[0-9a-fA-F]{8}$")
    private val roSent = ConcurrentHashMap.newKeySet<String>()

    /** The account folders and the profile-picture folders must be readable by the server (sent once per PS4 per app run). */
    private fun ensureRo(ps4: Ps4) { if (roSent.add(ps4.id)) runCatching { EzServer.setRoExtra(ps4, EzServer.roExtra().joinToString("\n")) } }

    fun accounts(ps4: Ps4): Pair<List<Account>, List<String>> {
        ensureRo(ps4)
        val notes = ArrayList<String>(); val out = ArrayList<Account>()
        val l = try { EzServer.fsList(ps4, "/user/home", 8000) } catch (e: Exception) { notes += "/user/home: ${e.message}"; return out to notes }
        for (d in l.filter { it.isDir && HEX8.matches(it.name) }) {
            val id = d.name.lowercase()
            val name = runCatching { String(EzServer.fsRead(ps4, "/user/home/${d.name}/username.dat", 0, 64, 5000), Charsets.UTF_8).trim { c -> c == '\u0000' || c.isWhitespace() } }.getOrDefault("").ifBlank { id }
            val av = listOf("avatar.png", "avatar440.png", "avatar260.png", "avatar128.png", "avatar64.png").flatMap { f ->
                listOf(id, "0x$id", d.name).distinct().map { "/system_data/priv/cache/profile/$it/$f" } }
            out += Account(d.name, name, av)
        }
        if (out.isEmpty()) notes += "No account folders found under /user/home."
        return out to notes
    }

    private fun be32(b: ByteArray, o: Int) = ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
    private fun be64(b: ByteArray, o: Int) = (be32(b, o) shl 32) or be32(b, o + 4)

    /** The game's NPWR id(s) from npbind.dat (searched as text: the file is a small binary table of ids). */
    /** Folders where a game's own files may be: the app-meta folders hold the extracted sce_sys files (param.sfo, npbind.dat, trophy/), so they come first. */
    fun baseDirs(titleId: String): List<String> = Ps4Info.META_DIRS.map { "$it/$titleId" } + listOf("/user/app/$titleId/sce_sys", "/user/app/$titleId")

    fun npwrIds(ps4: Ps4, titleId: String, notes: MutableList<String> = ArrayList()): List<String> {
        for (base in baseDirs(titleId)) {
            val p = "$base/npbind.dat"
            val b = try { EzServer.fsRead(ps4, p, 0, 256 * 1024, 6000) } catch (e: Exception) { notes += "$p: ${e.message}"; continue }
            val ids = NPWR.findAll(String(b, Charsets.ISO_8859_1)).map { it.value }.distinct().toList()
            if (ids.isNotEmpty()) return ids
            notes += "$p: read (${b.size} bytes) but no NPWR id inside"
        }
        // last resort: the console's shared trophy folder is named by NPWR id (only usable when exactly one is found for this game, which cannot be known here): not guessed
        return emptyList()
    }

    private fun unesc(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").trim()

    fun loadPack(ps4: Ps4, titleId: String, npwrs: List<String>, notes: MutableList<String> = ArrayList()): TrophyPack? {
        val dirs = baseDirs(titleId).map { "$it/trophy" } + npwrs.flatMap { listOf("/user/trophy/conf/$it", "/user/trophy/conf/${it.substringBefore('_')}") }
        for (dir in dirs) loadPackFrom(ps4, dir, npwrs, notes)?.let { return it }
        return null
    }

    private fun loadPackFrom(ps4: Ps4, dir: String, npwrs: List<String>, notes: MutableList<String>): TrophyPack? {
        val trps = try { EzServer.fsList(ps4, dir, 8000).filter { !it.isDir && it.name.endsWith(".trp", true) }.sortedBy { it.name } } catch (e: Exception) { notes += "$dir: ${e.message}"; return null }
        if (trps.isEmpty()) notes += "$dir: no .trp file"
        for (t in trps) {
            val path = "$dir/${t.name}"
            val head = try { EzServer.fsRead(ps4, path, 0, 64, 8000) } catch (e: Exception) { continue }
            if (head.size < 64 || be32(head, 0) != 0xDCA24D00L) continue
            val count = be32(head, 0x10).toInt(); val elSize = be32(head, 0x14).toInt().let { if (it in 0x20..0x100) it else 0x40 }
            if (count !in 1..4000) continue
            val table = try { EzServer.fsRead(ps4, path, 0x40, count * elSize, 15000) } catch (e: Exception) { continue }
            if (table.size < count * elSize) continue
            val files = LinkedHashMap<String, Pair<Long, Long>>()
            for (i in 0 until count) {
                val o = i * elSize
                val nm = String(table, o, 32, Charsets.ISO_8859_1).substringBefore('\u0000')
                files[nm] = be64(table, o + 32) to be64(table, o + 40)
            }
            val cfg = files.entries.firstOrNull { it.key.endsWith(".SFM", true) }
            val xml = cfg?.let { (_, v) -> runCatching { String(EzServer.fsRead(ps4, path, v.first, v.second.coerceAtMost(2L shl 20).toInt(), 15000), Charsets.UTF_8) }.getOrNull() }
            val list = ArrayList<Trophy>(); var title = ""; var readable = false
            if (xml != null && xml.contains("<trophyconf", true)) {
                title = Regex("<title-name>(.*?)</title-name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.let(::unesc) ?: ""
                for (m in Regex("<trophy\\s+([^>]*)>(.*?)</trophy>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
                    val a = m.groupValues[1]; val body = m.groupValues[2]
                    fun attr(k: String) = Regex("\\b$k\\s*=\\s*\"([^\"]*)\"").find(a)?.groupValues?.get(1)
                    val id = attr("id")?.toIntOrNull() ?: continue
                    val grade = attr("ttype")?.firstOrNull()?.uppercaseChar() ?: '?'
                    val name = Regex("<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.let(::unesc) ?: ""
                    val detail = Regex("<detail>(.*?)</detail>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.let(::unesc) ?: ""
                    list += Trophy(id, name.ifBlank { "Trophy %03d".format(id) }, detail, grade, attr("hidden").equals("yes", true), files.keys.firstOrNull { it.equals("TROP%03d.PNG".format(id), true) })
                }
                readable = list.isNotEmpty()
            }
            if (list.isEmpty()) {            // names are encrypted / absent: number the trophies from their pictures
                files.keys.mapNotNull { n -> Regex("^TROP(\\d{3})\\.PNG$", RegexOption.IGNORE_CASE).find(n)?.let { n to it.groupValues[1].toInt() } }
                    .sortedBy { it.second }.forEach { (n, id) -> list += Trophy(id, "Trophy %03d".format(id), "", '?', false, n) }
            }
            if (list.isEmpty()) continue
            val npwr = Regex("NPWR\\d{5}_\\d{2}").find(t.name)?.value ?: npwrs.getOrNull(trps.indexOf(t)) ?: npwrs.firstOrNull() ?: ""
            return TrophyPack(npwr, title, list.sortedBy { it.id }, path, files, readable)
        }
        return null
    }

    private val sig = ConcurrentHashMap<String, String>()
    private fun localCopy(ps4: Ps4, path: String, e: FsEntry, cache: File): File? {
        val f = File(cache, "trop_" + (ps4.id + path).hashCode().toUInt().toString(16) + ".db")
        val s = "${e.size}|${e.mtime}"
        if (f.exists() && sig[f.name] == s) return f
        return try { f.outputStream().use { EzServer.fsCopyTo(ps4, path, e.size, it) { } }; sig[f.name] = s; f } catch (ex: Exception) { f.delete(); null }
    }

    private fun walkDbs(ps4: Ps4, base: String, depth: Int, out: MutableList<Pair<String, FsEntry>>, notes: MutableList<String>) {
        val l = try { EzServer.fsList(ps4, base, 8000) } catch (e: Exception) { if (depth == 0) notes += "$base: ${e.message}"; return }
        for (e in l) {
            val p = "$base/${e.name}"
            if (e.isDir) { if (depth < 4 && out.size < 80) walkDbs(ps4, p, depth + 1, out, notes) }
            else if (e.name.endsWith(".db", true) && e.size in 1..(64L shl 20)) out += p to e
        }
    }

    private fun toSeconds(v: Long): Long = when { v > 100_000_000_000_000L -> v / 1_000_000; v > 100_000_000_000L -> v / 1000; else -> v }

    /** Unlocked trophies of [account] for the game with NPWR id [npwr]. */
    fun state(ps4: Ps4, account: Account, npwr: String, cache: File): TrophyState {
        val notes = ArrayList<String>(); val dbs = ArrayList<Pair<String, FsEntry>>()
        walkDbs(ps4, "/user/home/${account.id}/trophy", 0, dbs, notes)
        if (dbs.isEmpty()) { notes += "No trophy database found for this account."; return TrophyState(null, notes) }
        val key = npwr.substringBefore('_').removePrefix("NPWR")
        val ordered = dbs.sortedByDescending { it.first.contains("NPWR$key", true) }
        val unlocked = HashMap<Int, Long>(); var any = false
        for ((p, e) in ordered) {
            val f = localCopy(ps4, p, e, cache) ?: run { notes += "$p: could not be copied"; null } ?: continue
            val perTitle = p.contains("NPWR$key", true)
            try {
                val db = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
                try {
                    val tables = ArrayList<String>()
                    db.rawQuery("select name from sqlite_master where type='table'", null).use { c -> while (c.moveToNext()) tables.add(c.getString(0)) }
                    for (t in tables) {
                        val cols = db.rawQuery("select * from \"$t\" limit 0", null).use { it.columnNames.toList() }
                        val low = cols.map { it.lowercase() }
                        fun pick(f2: (String) -> Boolean) = cols.getOrNull(low.indexOfFirst(f2)).takeIf { low.any(f2) }
                        val idCol = pick { it in listOf("trophy_id", "trop_id", "trophyid", "trophy_no", "trophy_num", "id", "no") } ?: continue
                        val flagCol = pick { it.contains("unlock") || it.contains("earn") || it.contains("achiev") || it == "flag" || it == "status" }
                        val tsCol = pick { it.contains("time") || it.contains("date") }
                        if (flagCol == null && tsCol == null) continue
                        val titleCol = pick { it.contains("npcomm") || it.contains("np_comm") || it.contains("np_com") || it.contains("npwr") || it == "title_id" || it.contains("communication") }
                        val where = when { titleCol != null -> " where cast(\"$titleCol\" as text) like '%NPWR$key%'"; perTitle -> ""; else -> continue }
                        val sel = listOfNotNull("\"$idCol\"", flagCol?.let { "\"$it\"" }, tsCol?.let { "\"$it\"" }).joinToString()
                        db.rawQuery("select $sel from \"$t\"$where", null).use { c ->
                            while (c.moveToNext()) {
                                val id = c.getString(0)?.toIntOrNull() ?: continue
                                var i = 1
                                val flagOk = if (flagCol != null) { val s = c.getString(i++); s != null && s != "0" && s.isNotBlank() } else true
                                val ts = if (tsCol != null) c.getString(i)?.toLongOrNull() ?: 0L else 0L
                                any = true
                                if (flagOk && (flagCol != null || ts > 0)) unlocked[id] = toSeconds(ts)
                            }
                        }
                    }
                } finally { db.close() }
            } catch (ex: Exception) { notes += "$p: ${ex.message}" }
            if (any && unlocked.isNotEmpty()) break
        }
        if (!any) { notes += "Found ${dbs.size} database file(s) (${dbs.take(4).joinToString { it.first.substringAfterLast('/') }}) but none matched the expected trophy layout."; return TrophyState(null, notes) }
        return TrophyState(unlocked, notes)
    }

    fun points(g: Char) = when (g) { 'P' -> 300; 'G' -> 90; 'S' -> 30; 'B' -> 15; else -> 0 }
}
