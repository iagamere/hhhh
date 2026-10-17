@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.abdo.ps4monitor
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable fun Ps4InfoScreen(nav: NavController) {
    val ps4s by Ps4Repo.list.collectAsState(); val activeId by Ps4Repo.activeId.collectAsState()
    val ps4 = ps4s.firstOrNull { it.id == activeId }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(true) }
    var reason by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ps4?.id, reload) {
        val p = ps4 ?: return@LaunchedEffect
        checking = true
        reason = withContext(Dispatchers.IO) { EzServer.prepareInfo(p) }
        checking = false
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BackHeader(tr("PS4 information", "معلومات الـPS4"), nav) { IconButton(onClick = { reload++ }) { Ico(R.drawable.ic_refresh) } }
        if (ps4 == null) EmptyState(R.drawable.ic_console, tr("Add a PS4 first.", "أضف جهاز PS4 أولًا."))
        else if (checking) { LinearProgressIndicator(Modifier.fillMaxWidth()); Dim(tr("Contacting ${ps4.name}…", "جارٍ الاتصال بـ ${ps4.name}…")) }
        else if (reason != null) Panel {
            Text(Tx.t(reason!!))
            Button(onClick = { reload++ }) { Ico(R.drawable.ic_refresh, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Try again", "إعادة المحاولة")) }
        } else {
            val tabs = listOf(tr("Captures", "اللقطات"), tr("Storage", "التخزين"), tr("Games", "الألعاب"), tr("Device", "الجهاز"))
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) { tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Lbl(t) }) } }
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> CapturesTab(ps4, reload)
                    1 -> StorageTab(ps4, reload)
                    2 -> GamesTab(ps4, reload)
                    else -> DeviceTab(ps4, reload)
                }
            }
        }
    }
}

@Composable private fun NoteList(notes: List<String>) {
    if (notes.isEmpty()) return
    Panel { Dim(tr("Details from the console (technical):", "تفاصيل من الجهاز (تقنية):")); notes.take(8).forEach { Dim(it) } }
}

// ---------------------------------------------------------------- captures
@Composable private fun CapturesTab(ps4: Ps4, reload: Int) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var scan by remember(ps4.id, reload) { mutableStateOf<CaptureScan?>(null) }
    LaunchedEffect(ps4.id, reload) { scan = null; scan = withContext(Dispatchers.IO) { Ps4Info.scanCaptures(ps4) } }
    var videos by rememberSaveable { mutableStateOf(false) }
    var openCap by remember { mutableStateOf<Capture?>(null) }
    var pending by remember { mutableStateOf<Capture?>(null) }
    var saveMsg by remember { mutableStateOf("") }
    var saveProg by remember { mutableFloatStateOf(-1f) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val c = pending; pending = null
        if (uri != null && c != null) scope.launch {
            saveProg = 0f; saveMsg = ""
            val err: String? = withContext(Dispatchers.IO) {
                try {
                    val size = if (c.size > 0) c.size else EzServer.fsSize(ps4, c.path, 6000)
                    val os = ctx.contentResolver.openOutputStream(uri)
                    if (size <= 0) "The file is empty or could not be read."
                    else if (os == null) "Could not open the destination file."
                    else { os.use { EzServer.fsCopyTo(ps4, c.path, size, it) { d -> saveProg = (d.toDouble() / size).toFloat() } }; null }
                } catch (e: Exception) { e.message ?: "Copy failed" }
            }
            saveProg = -1f
            saveMsg = if (err == null) tr("Saved to your phone.", "حُفظ على هاتفك.") else tr("Could not save: ", "تعذّر الحفظ: ") + err
        }
    }
    val s = scan
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Dim(tr("Reading the capture folders…", "جارٍ قراءة مجلدات اللقطات…")); return@Column }
        val photos = s.items.filter { !it.video }; val vids = s.items.filter { it.video }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!videos, { videos = false }, label = { Lbl(tr("Screenshots", "لقطات الشاشة") + " (${photos.size})") })
            FilterChip(videos, { videos = true }, label = { Lbl(tr("Videos", "الفيديوهات") + " (${vids.size})") })
        }
        if (saveProg >= 0f) { LinearProgressIndicator(progress = { saveProg }, modifier = Modifier.fillMaxWidth()); Dim(tr("Copying to your phone…", "جارٍ النسخ إلى هاتفك…")) }
        else if (saveMsg.isNotBlank()) Dim(saveMsg)
        if (s.truncated) Dim(tr("Only the first files are shown (the folders are large).", "تظهر الملفات الأولى فقط (المجلدات كبيرة)."))
        val shown = if (videos) vids else photos
        if (shown.isEmpty()) {
            EmptyState(R.drawable.ic_folder, if (videos) tr("No videos found.", "لا توجد فيديوهات.") else tr("No screenshots found.", "لا توجد لقطات شاشة."))
            NoteList(s.notes + (if (s.items.isEmpty()) listOf(tr("Open the Device tab and use \"Check paths\" to see which folders the console answers.", "افتح تبويب الجهاز واستعمل «فحص المسارات» لترى أي المجلدات يردّ عليها الجهاز.")) else emptyList()))
        } else LazyVerticalGrid(GridCells.Adaptive(110.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            gridItems(shown, key = { it.path }) { c -> CaptureCell(ps4, c) { openCap = c } }
        }
    }
    openCap?.let { c ->
        AlertDialog(onDismissRequest = { openCap = null },
            title = { Text(c.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!c.video) {
                        val bmp by produceState<Bitmap?>(Thumbs.cached("${ps4.id}|${c.path}|1600"), c.path) { if (value == null) value = Thumbs.load(ps4, c.path, c.size, 1600) }
                        val b = bmp
                        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
                        else Dim(tr("No preview (file too large or not readable). You can still save it.", "لا توجد معاينة (الملف كبير أو غير مقروء). يمكنك حفظه مع ذلك."))
                    } else Dim(tr("Videos are not previewed here. Save it to your phone to watch it.", "لا تُعرض معاينة للفيديو هنا. احفظه على هاتفك لمشاهدته."))
                    Dim(Fmt.bytes(c.size) + "  •  " + Fmt.dt(c.mtime))
                    if (c.titleId.isNotEmpty()) Dim((Ps4Info.names[c.titleId]?.let { "$it  •  " } ?: "") + c.titleId)
                    Dim(c.path)
                }
            },
            confirmButton = { TextButton(enabled = saveProg < 0f, onClick = { pending = c; openCap = null; saver.launch(c.name) }) { Lbl(tr("Save to phone", "حفظ على الهاتف")) } },
            dismissButton = { TextButton(onClick = { openCap = null }) { Lbl(tr("Close", "إغلاق")) } })
    }
}

@Composable private fun CaptureCell(ps4: Ps4, c: Capture, onClick: () -> Unit) {
    val bmp by produceState<Bitmap?>(Thumbs.cached("${ps4.id}|${c.path}|320"), c.path) { if (value == null && !c.video) value = Thumbs.load(ps4, c.path, c.size, 320) }
    Card(onClick = onClick, shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                val b = bmp
                if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Ico(if (c.video) R.drawable.ic_play else R.drawable.ic_file, 30.dp, MaterialTheme.colorScheme.outline)
            }
            Column(Modifier.padding(6.dp)) {
                Dim(Ps4Info.names[c.titleId] ?: c.titleId.ifEmpty { c.name }, maxLines = 1)
                Dim(Fmt.bytes(c.size) + if (c.mtime > 0) " • " + Fmt.dt(c.mtime).substringBefore(' ') else "", maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------- storage
@Composable private fun StorageTab(ps4: Ps4, reload: Int) {
    var res by remember(ps4.id, reload) { mutableStateOf<Pair<List<Volume>, List<String>>?>(null) }
    LaunchedEffect(ps4.id, reload) { res = null; res = withContext(Dispatchers.IO) { Ps4Info.storage(ps4) } }
    val r = res
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (r == null) { item { LinearProgressIndicator(Modifier.fillMaxWidth()) }; return@LazyColumn }
        if (r.first.isEmpty()) item { EmptyState(R.drawable.ic_folder, tr("The console did not report any storage.", "لم يُبلغ الجهاز عن أي تخزين.")) }
        items(r.first, key = { it.path }) { v ->
            val used = (v.total - v.free).coerceAtLeast(0)
            Panel {
                Text(v.label, fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(progress = { (used.toDouble() / v.total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(tr("Free", "المتبقي") + ": " + Fmt.bytes(v.free) + "   •   " + tr("Used", "المستخدم") + ": " + Fmt.bytes(used))
                Dim(tr("Total", "الإجمالي") + ": " + Fmt.bytes(v.total) + "  •  " + v.path)
            }
        }
        item { NoteList(r.second) }
    }
}

// ---------------------------------------------------------------- games
@Composable private fun GamesTab(ps4: Ps4, reload: Int) {
    var res by remember(ps4.id, reload) { mutableStateOf<Pair<List<GameInfo>, List<String>>?>(null) }
    var done by remember(ps4.id, reload) { mutableIntStateOf(0) }; var total by remember(ps4.id, reload) { mutableIntStateOf(0) }
    var q by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(ps4.id, reload) { res = null; res = withContext(Dispatchers.IO) { Ps4Info.scanGames(ps4) { d, t -> done = d; total = t } } }
    val r = res
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (r == null) { LinearProgressIndicator(progress = { if (total > 0) done.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth()); Dim(tr("Reading installed games…", "جارٍ قراءة الألعاب المثبتة…") + if (total > 0) " $done/$total" else ""); return@Column }
        if (r.first.isEmpty()) { EmptyState(R.drawable.ic_package, tr("No installed games found.", "لم يُعثر على ألعاب مثبتة.")); NoteList(r.second); return@Column }
        OutlinedTextField(q, { q = it }, label = { Text(tr("Search", "بحث") + " (${r.first.size})") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val list = r.first.filter { q.isBlank() || it.title.contains(q, true) || it.titleId.contains(q, true) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list, key = { it.titleId }) { g -> GameRow(ps4, g) }
            item { NoteList(r.second) }
        }
    }
}

@Composable private fun GameRow(ps4: Ps4, g: GameInfo) {
    val icon by produceState<Bitmap?>(g.iconPath?.let { Thumbs.cached("${ps4.id}|$it|128") }, g.iconPath) { val p = g.iconPath; if (value == null && p != null) value = Thumbs.load(ps4, p, 0, 128) }
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                val b = icon
                if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Ico(R.drawable.ic_package, 28.dp, MaterialTheme.colorScheme.outline)
            }
            Column(Modifier.weight(1f)) {
                Text(g.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Dim(listOf(g.titleId, g.version.takeIf { it.isNotBlank() }?.let { "v$it" }, Ps4Info.categoryLabel(g.category).takeIf { it.isNotBlank() }).filterNotNull().joinToString("  •  "), maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------- device + diagnostics
@Composable private fun DeviceTab(ps4: Ps4, reload: Int) {
    val scope = rememberCoroutineScope()
    var sys by remember(ps4.id, reload) { mutableStateOf<Pair<List<Pair<String, String>>, List<String>>?>(null) }
    var checks by remember(ps4.id, reload) { mutableStateOf<List<PathCheck>?>(null) }
    var checking by remember { mutableStateOf(false) }
    var extra by remember(ps4.id) { mutableStateOf(EzServer.roExtra().joinToString("\n")) }
    var msg by remember { mutableStateOf("") }
    var info by remember(ps4.id, reload) { mutableStateOf<EzServer.ServerInfo?>(null) }
    LaunchedEffect(ps4.id, reload) { sys = null; val (i, s) = withContext(Dispatchers.IO) { EzServer.info(ps4) to Ps4Info.deviceRows(EzServer.sysinfo(ps4)) }; info = i; sys = s }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Panel {
                Text(ps4.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Dim("${ps4.host}  •  web :${ps4.httpPort}")
                info?.let { Dim(tr("ezRemote Server", "خادم ezRemote") + ": " + it.version) }
                val st = sys
                if (st == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else {
                    st.first.forEach { (k, v) -> Row { Text(k, Modifier.weight(0.45f), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v, Modifier.weight(0.55f)) } }
                    if (st.first.isEmpty()) Dim(tr("The console did not report system details.", "لم يُبلغ الجهاز عن تفاصيل النظام."))
                    if (st.second.isNotEmpty()) Dim(tr("Not reported by this console: ", "لم يُبلَّغ عنها من هذا الجهاز: ") + st.second.joinToString())
                }
            }
        }
        item {
            Panel {
                Text(tr("Folders the app may read", "المجلدات التي يقرؤها التطبيق"), fontWeight = FontWeight.SemiBold)
                Dim(tr("Read-only: the server never changes these. Add more folders below (one per line), e.g. where your captures really are.", "للقراءة فقط: لا يغيّرها الخادم أبدًا. أضف مجلدات أخرى أدناه (واحد في كل سطر)، مثل المكان الفعلي للقطاتك."))
                OutlinedTextField(extra, { extra = it }, label = { Text(tr("Extra folders", "مجلدات إضافية")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { val e = withContext(Dispatchers.IO) { EzServer.setRoExtra(ps4, extra) }; msg = if (e == null) tr("Saved and sent to the server.", "حُفظ وأُرسل إلى الخادم.") else Tx.t(e) } }) { Lbl(tr("Save", "حفظ")) }
                    FilledTonalButton(enabled = !checking, onClick = { scope.launch { checking = true; checks = null; checks = withContext(Dispatchers.IO) { Ps4Info.checkPaths(ps4) }; checking = false } }) { Lbl(tr("Check paths", "فحص المسارات")) }
                }
                if (msg.isNotBlank()) Dim(msg)
                if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        checks?.let { l ->
            items(l, key = { it.path }) { c ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Ico(if (c.ok) R.drawable.ic_check_circle else R.drawable.ic_error, 20.dp, if (c.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Column { Text(c.path, style = MaterialTheme.typography.bodyMedium); Dim(c.detail) }
                }
            }
        }
    }
}
