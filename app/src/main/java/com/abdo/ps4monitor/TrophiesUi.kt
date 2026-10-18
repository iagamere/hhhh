@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.abdo.ps4monitor
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PLAT = Color(0xFF7FB2E5); private val GOLD = Color(0xFFE2B33C); private val SILV = Color(0xFFB4BCC6); private val BRON = Color(0xFFC07A3E)
private fun gradeColor(g: Char) = when (g) { 'P' -> PLAT; 'G' -> GOLD; 'S' -> SILV; 'B' -> BRON; else -> Color(0xFF8A8F98) }
private fun gradeName(g: Char) = when (g) { 'P' -> tr("Platinum", "بلاتينية"); 'G' -> tr("Gold", "ذهبية"); 'S' -> tr("Silver", "فضية"); 'B' -> tr("Bronze", "برونزية"); else -> tr("Trophy", "كأس") }

private class TrophyData(val accounts: List<Account>, val pack: TrophyPack?, val npwrs: List<String>, val notes: List<String>)

/** Ring with the progress of one account around its picture (PS4 style circles). */
@Composable private fun AccountCircle(ps4: Ps4, a: Account, pct: Int?, selected: Boolean, onClick: () -> Unit) {
    val img by produceState<Bitmap?>(Thumbs.peekAny(ps4, a.avatarPaths, 128), a.id) { if (value == null) value = Thumbs.loadAny(ps4, a.avatarPaths, 128) }
    val ring = MaterialTheme.colorScheme.primary; val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(Modifier.width(84.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val w = if (selected) 7.dp.toPx() else 5.dp.toPx(); val inset = w / 2
                val sz = Size(size.width - w, size.height - w)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(w))
                if (pct != null) drawArc(ring, -90f, 360f * pct / 100f, false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
            }
            Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                val b = img
                if (b != null) Image(b.asImageBitmap(), a.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(a.name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Text(a.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        Dim(if (pct != null) "$pct%" else "—", maxLines = 1)
    }
}

@Composable private fun TrophyRow(ps4: Ps4, pack: TrophyPack, t: Trophy, earnedAt: Long?, known: Boolean) {
    val got = earnedAt != null
    val icon by produceState<Bitmap?>(null, t.id, pack.trpPath) {
        val e = t.icon?.let { pack.files[it] }
        if (e != null && e.second in 1..(2L shl 20)) value = withContext(Dispatchers.IO) { runCatching { Thumbs.decode(EzServer.fsRead(ps4, pack.trpPath, e.first, e.second.toInt(), 15000), 160) }.getOrNull() }
    }
    val hiddenLocked = t.hidden && !got
    Row(Modifier.fillMaxWidth().alpha(if (got || !known) 1f else 0.55f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            val b = icon
            if (b != null && !hiddenLocked) Image(b.asImageBitmap(), t.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Ico(R.drawable.ic_package, 24.dp, MaterialTheme.colorScheme.outline)
        }
        Column(Modifier.weight(1f)) {
            Text(if (hiddenLocked) tr("Hidden trophy", "كأس مخفي") else t.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!hiddenLocked && t.detail.isNotBlank()) Dim(t.detail, maxLines = 3)
            if (got && earnedAt != null && earnedAt > 0) Dim(tr("Earned ", "حُقّق ") + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(earnedAt * 1000)))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(gradeColor(t.grade)))
            if (t.grade != '?') Dim(gradeName(t.grade), maxLines = 1)
            if (known) Dim(if (got) "✓" else "🔒", maxLines = 1)
        }
    }
}

/** Per-game trophy library: account circles with progress, then the earned / not earned trophies of the chosen account. */
@Composable internal fun TrophiesTab(ps4: Ps4, g: GameInfo) {
    val ctx = LocalContext.current
    var data by remember(ps4.id, g.titleId) { mutableStateOf<TrophyData?>(null) }
    LaunchedEffect(ps4.id, g.titleId) {
        data = withContext(Dispatchers.IO) {
            val (acc, n1) = TrophyLib.accounts(ps4)
            val npwrs = TrophyLib.npwrIds(ps4, g.titleId)
            TrophyData(acc, TrophyLib.loadPack(ps4, g.titleId, npwrs), npwrs, n1)
        }
    }
    val d = data
    if (d == null) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Dim(tr("Reading trophies…", "جارٍ قراءة الإنجازات…")) }; return }
    val pack = d.pack
    if (pack == null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EmptyState(R.drawable.ic_package, tr("This game has no trophy file the app can read.", "هذه اللعبة لا تحتوي ملف إنجازات يستطيع التطبيق قراءته."))
            NoteList(d.notes + listOf(if (d.npwrs.isEmpty()) "No NPWR id in npbind.dat of ${g.titleId}." else "NPWR: ${d.npwrs.joinToString()}; no readable .trp in /user/app/${g.titleId}/sce_sys/trophy."))
        }
        return
    }
    val states = remember(ps4.id, g.titleId) { mutableStateMapOf<String, TrophyState>() }
    LaunchedEffect(pack.trpPath, d.accounts) {
        for (a in d.accounts) if (a.id !in states) states[a.id] = withContext(Dispatchers.IO) { TrophyLib.state(ps4, a, pack.npwr, ctx.cacheDir) }
    }
    var sel by remember(g.titleId) { mutableStateOf<String?>(null) }
    val total = pack.trophies.size
    fun pctOf(a: Account): Int? = states[a.id]?.unlocked?.let { u -> if (total == 0) 0 else (u.keys.count { k -> pack.trophies.any { it.id == k } } * 100 / total) }
    // by default the account with the most progress (the one that played)
    val shownId = sel ?: d.accounts.maxByOrNull { pctOf(it) ?: -1 }?.id
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (pack.title.isNotBlank()) Dim(pack.title)
        if (d.accounts.isEmpty()) Dim(tr("No accounts found on the console.", "لم يُعثر على حسابات في الجهاز."))
        else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            d.accounts.forEach { a -> AccountCircle(ps4, a, pctOf(a), a.id == shownId) { sel = a.id } }
        }
        val acc = d.accounts.firstOrNull { it.id == shownId }
        val st = acc?.let { states[it.id] }
        if (acc != null && st == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Dim(tr("Reading ${acc.name}'s progress…", "جارٍ قراءة تقدّم ${acc.name}…")) }
        if (acc != null && st != null) {
            val u = st.unlocked
            val earned = pack.trophies.filter { u?.containsKey(it.id) == true }
            val pct = if (u != null && total > 0) earned.size * 100 / total else null
            Panel {
                Text(acc.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                if (pct != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("$pct%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Dim(tr("${earned.size} of $total trophies", "${earned.size} من $total إنجازًا"))
                    }
                    LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                    if (pack.namesReadable) FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        listOf('P', 'G', 'S', 'B').forEach { gr ->
                            val all = pack.trophies.count { it.grade == gr }
                            if (all > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(gradeColor(gr))); Text("${earned.count { it.grade == gr }}/$all", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (pack.namesReadable) Dim(tr("Points: ", "النقاط: ") + earned.sumOf { TrophyLib.points(it.grade) })
                } else Dim(tr("This console's unlock data for this account could not be read, so the list below shows every trophy without earned marks.", "تعذّرت قراءة بيانات الإنجازات المحققة لهذا الحساب، لذلك تظهر القائمة بلا علامات تحقيق."))
            }
            if (u != null) {
                val missing = pack.trophies.filter { u[it.id] == null }
                if (earned.isNotEmpty()) { SectionTitle(tr("Earned", "المحققة") + " (${earned.size})"); earned.sortedByDescending { u[it.id] ?: 0L }.forEach { TrophyRow(ps4, pack, it, u[it.id], true) } }
                if (missing.isNotEmpty()) { SectionTitle(tr("Not earned yet", "غير محققة") + " (${missing.size})"); missing.forEach { TrophyRow(ps4, pack, it, null, true) } }
            } else pack.trophies.forEach { TrophyRow(ps4, pack, it, null, false) }
            if (st.unlocked == null) NoteList(st.notes + tr("Device tab > Scan trophies: send the result so the reader can be matched to this console.", "تبويب الجهاز > فحص الإنجازات: أرسل النتيجة ليُضبط القارئ على هذا الجهاز."))
        }
        if (!pack.namesReadable) Dim(tr("The trophy names in this game's file are encrypted, so trophies are numbered (their pictures are real).", "أسماء الإنجازات في ملف اللعبة مشفّرة، لذلك تظهر مرقّمة (الصور حقيقية)."))
    }
}
