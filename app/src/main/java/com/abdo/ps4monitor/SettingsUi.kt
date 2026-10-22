@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
package com.abdo.ps4monitor
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable private fun NavRow(icon: Int, title: String, sub: String, onClick: () -> Unit) =
    Card(onClick = onClick, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Ico(icon, 24.dp, MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); if (sub.isNotEmpty()) Dim(sub, maxLines = 2) }
            Ico(R.drawable.ic_arrow_forward, 20.dp, MaterialTheme.colorScheme.outline)
        }
    }

@Composable fun SettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val s0 = remember { Store.settings() }
    var iv by remember { mutableIntStateOf(s0.interval) }; var st by remember { mutableIntStateOf(s0.stuck) }
    val ignoring = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { Text(tr("Settings", "الإعدادات"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
        item { NavRow(R.drawable.ic_console, tr("PS4 consoles", "أجهزة PS4"), tr("Add, edit and choose the active PS4", "إضافة وتعديل واختيار الجهاز النشط")) { nav.navigate("settings/ps4s") } }
        item { NavRow(R.drawable.ic_download, tr("PS4 server", "خادم PS4"), tr("Status, send the server to the PS4, download settings, log", "الحالة، إرسال السيرفر إلى الـPS4، إعدادات التحميل، السجل")) { nav.navigate("settings/server") } }
        item { Panel {
            SectionTitle(tr("Language", "اللغة"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0 to tr("Phone", "الهاتف"), 1 to "English", 2 to "العربية").forEach { (i, t) -> FilterChip(Lang.mode == i, { Store.setLang(i) }, label = { Lbl(t) }) } }
            SectionTitle(tr("Appearance", "المظهر"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(tr("System", "النظام"), tr("Light", "فاتح"), tr("Dark", "داكن")).forEachIndexed { i, t -> FilterChip(Store.theme.intValue == i, { Store.setTheme(i) }, label = { Lbl(t) }) } }
            if (android.os.Build.VERSION.SDK_INT >= 31) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { Text(tr("Use phone colours", "استخدام ألوان الهاتف")); Dim(tr("Follows your wallpaper / system accent colour", "يتبع لون الهاتف الأساسي")) }
                Switch(Store.dynamic.value, { Store.setDynamic(it) })
            }
        } }
        item { PowerPanel() }
        item { Panel {
            SectionTitle(tr("Monitoring", "المراقبة"))
            Text(tr("Polling interval", "فاصل الفحص")); Chips(listOf(2, 3, 5, 10), iv, tr("s", "ث")) { iv = it; Store.putInt("poll", it) }
            Text(tr("Stalled after no growth for", "يُعتبر متعثّرًا بعد عدم نمو لمدة")); Chips(listOf(30, 60, 120, 300), st, tr("s", "ث")) { st = it; Store.putInt("stuck", it) }
            HorizontalDivider()
            ToggleRow(tr("Download notifications", "إشعارات التحميل"), "notif", true)
            ToggleRow(tr("Alert me when a download is interrupted", "نبّهني عند انقطاع تحميل"), "alert_int", true)
        } }
        if (!ignoring) item { Panel {
            Text(tr("Battery optimization may pause background monitoring on some phones.", "قد يوقف توفير البطارية المراقبة في الخلفية على بعض الهواتف."))
            Button(onClick = { ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Lbl(tr("Battery settings", "إعدادات البطارية")) }
        } }
        item { NavRow(R.drawable.ic_search, tr("Advanced", "متقدم"), tr("Debug log, stop PS4 server", "سجل التصحيح، إيقاف خادم PS4")) { nav.navigate("settings/advanced") } }
        item { Dim("PS4 Download Monitor 4.2") }
    }
}

/** Turn the active PS4 off now, or after a chosen time. Both put the console in rest mode (the only thing the standby payload does). */
@Composable fun PowerPanel() {
    val ps4 by Ps4Repo.list.collectAsState(); val activeId by Ps4Repo.activeId.collectAsState()
    val p = ps4.firstOrNull { it.id == activeId } ?: ps4.firstOrNull()
    val plan by DownloadMonitor.sleepPlan.collectAsState()
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf("") }
    var left by remember { mutableLongStateOf(0L) }
    LaunchedEffect(plan?.endsAt) { while (plan != null) { left = maxOf(0L, ((plan?.endsAt ?: 0L) - System.currentTimeMillis()) / 1000); delay(1000) } }
    fun fmt(sec: Long) = if (sec >= 3600) "${sec / 3600} h ${(sec % 3600) / 60} min" else if (sec >= 60) "${sec / 60} min ${sec % 60} s" else "$sec s"
    fun start(min: Int) { val t = p ?: return; if (!Standby.available(ctx)) { msg = Standby.message("no-payload"); return }; msg = ""; DownloadMonitor.startSleepTimer(t, min) }
    Panel {
        SectionTitle(tr("Turn off the PS4", "إطفاء الـPS4"))
        if (p == null) { Dim(tr("Add a PS4 first.", "أضف جهاز PS4 أولاً.")); return@Panel }
        Dim(tr("For ${p.name}. Both buttons put the console in rest mode (it needs the standby payload, see the PS4's settings). Downloads on the PS4 are not waited for here.",
            "للجهاز ${p.name}. الزران يُدخلان الجهاز في وضع الراحة (يحتاجان payload الإطفاء، راجع إعدادات الجهاز). لا ينتظر هنا انتهاء التحميلات."))
        Button(enabled = !busy, onClick = { confirm = true }) { Lbl(tr("Turn off now", "أطفئ الآن")) }
        HorizontalDivider()
        Text(tr("Rest mode after…", "وضع الراحة بعد…"), fontWeight = FontWeight.SemiBold)
        if (plan != null) {
            Text(tr("Timer running: ${fmt(left)} left", "المؤقت يعمل: المتبقي ${fmt(left)}"))
            OutlinedButton(onClick = { DownloadMonitor.cancelPowerOff() }) { Lbl(tr("Cancel timer", "إلغاء المؤقت")) }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15 to tr("15 min", "15 د"), 30 to tr("30 min", "30 د"), 60 to tr("1 h", "ساعة"), 120 to tr("2 h", "ساعتان"), 240 to tr("4 h", "4 ساعات"), 480 to tr("8 h", "8 ساعات"))
                    .forEach { (m, t) -> FilledTonalButton(onClick = { start(m) }) { Lbl(t) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(custom, { custom = it.filter { c -> c.isDigit() }.take(4) }, label = { Text(tr("Minutes (custom)", "دقائق (مخصص)")) }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                Button(enabled = (custom.toIntOrNull() ?: 0) in 1..1440, onClick = { start(custom.toInt()) }) { Lbl(tr("Start", "ابدأ")) }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (msg.isNotEmpty()) Text(msg, fontWeight = FontWeight.SemiBold)
    }
    if (confirm && p != null) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(tr("Turn off ${p.name}?", "إطفاء ${p.name}؟")) },
        text = { Text(tr("The PS4 goes to rest mode now. Unfinished downloads on it may stop.", "سيدخل الجهاز وضع الراحة الآن. قد تتوقف التحميلات غير المكتملة عليه.")) },
        confirmButton = { TextButton(onClick = { confirm = false; busy = true; msg = ""; scope.launch { msg = DownloadMonitor.sleepNow(p); busy = false } }) { Lbl(tr("Turn off", "إطفاء")) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Lbl(tr("Cancel", "إلغاء")) } })
}

@Composable fun AdvancedScreen(nav: NavController) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var confirmStop by remember { mutableStateOf(false) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackHeader(tr("Advanced", "متقدم"), nav)
        NavRow(R.drawable.ic_search, tr("Debug log", "سجل التصحيح"), "") { nav.navigate("settings/log") }
        Panel {
            OutlinedButton(onClick = { confirmStop = true }, enabled = Ps4Repo.active() != null) { Ico(R.drawable.ic_stop, 18.dp, MaterialTheme.colorScheme.error); Spacer(Modifier.width(6.dp)); Lbl(tr("Stop PS4 server", "إيقاف خادم PS4"), color = MaterialTheme.colorScheme.error) }
            Dim(tr("Stops ALL its background downloads and installs; they resume from where they stopped once PS4 server is launched again on the PS4.", "يوقف كل تحميلاته وتثبيتاته في الخلفية؛ وتُستأنف من حيث توقفت عند تشغيل PS4 server مجددًا على الـPS4."))
        }
    }
    if (confirmStop) AlertDialog(onDismissRequest = { confirmStop = false }, title = { Text(tr("Stop PS4 server?", "إيقاف خادم PS4؟")) },
        text = { Text(tr("All background downloads and installs on ${Ps4Repo.active()?.name ?: "the PS4"} will stop. Launch PS4 server on the PS4 to start it again.", "ستتوقف كل التحميلات والتثبيتات في الخلفية على ${Ps4Repo.active()?.name ?: "الـPS4"}. شغّل PS4 server على الـPS4 لتشغيله مجددًا.")) },
        confirmButton = { TextButton(onClick = {
            val p = Ps4Repo.active(); confirmStop = false
            if (p != null) scope.launch {
                val stopped = withContext(Dispatchers.IO) { Ps4Server.stop(p) }
                Toast.makeText(ctx, if (stopped) tr("PS4 server stopped.", "توقف خادم PS4.") else tr("It still answers; it was not stopped.", "ما زال يستجيب؛ لم يتوقف."), Toast.LENGTH_LONG).show()
            }
        }) { Lbl(tr("Stop", "إيقاف"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Lbl(tr("Cancel", "إلغاء")) } })
}

@Composable fun LogScreen(nav: NavController) {
    val log by DownloadMonitor.log.collectAsState()
    Column(Modifier.padding(16.dp)) {
        BackHeader(tr("Debug log", "سجل التصحيح"), nav) { TextButton(onClick = { DownloadMonitor.clearLog() }) { Lbl(tr("Clear", "مسح")) } }
        if (log.isEmpty()) Text("(empty)")
        LazyColumn { items(log.reversed()) { Text(it, style = MaterialTheme.typography.bodySmall) } }     // raw technical text stays English on purpose
    }
}

@Composable fun Ps4ListScreen(nav: NavController) {
    val list by Ps4Repo.list.collectAsState(); val activeId by Ps4Repo.activeId.collectAsState()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BackHeader(tr("PS4 consoles", "أجهزة PS4"), nav)
        if (list.isEmpty()) EmptyState(R.drawable.ic_console, tr("No PS4 added yet.", "لم تُضف أي جهاز PS4 بعد."))
        list.forEach { p ->
            Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.padding(start = 6.dp, top = 8.dp, bottom = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = p.id == activeId, onClick = { Ps4Repo.setActive(p.id) })
                    Column(Modifier.weight(1f)) { Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Dim("${p.host}  •  ${p.dest}", maxLines = 1) }
                    TextButton(onClick = { nav.navigate("settings/ps4/${p.id}") }) { Lbl(tr("Edit", "تعديل")) }
                }
            }
        }
        Button(onClick = { nav.navigate("settings/ps4/new") }) { Ico(R.drawable.ic_add, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Add PS4", "إضافة PS4")) }
    }
}

@Composable fun Ps4EditScreen(id: String, nav: NavController) {
    val existing = remember(id) { Ps4Repo.get(id) }
    val isNew = existing == null
    var name by remember { mutableStateOf(existing?.name ?: "PS4") }
    var host by remember { mutableStateOf(existing?.host.orEmpty()) }
    var dest by remember { mutableStateOf(existing?.dest ?: "/data/pkg") }
    var token by remember { mutableStateOf(existing?.token.orEmpty()) }
    var wakeKey by remember { mutableStateOf(existing?.wakeKey.orEmpty()) }
    var autoWake by remember { mutableStateOf(existing?.autoWake ?: false) }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var hasStandby by remember { mutableStateOf(Standby.available(ctx)) }
    var standbyMsg by remember { mutableStateOf("") }
    var standbyBusy by remember { mutableStateOf(false) }
    var fullOff by remember { mutableStateOf(Standby.fullOff()) }
    val standbyPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri: android.net.Uri? ->
        if (uri != null) scope.launch {
            val err = withContext(Dispatchers.IO) { Standby.import(ctx, uri) }
            if (err != null) standbyMsg = Tx.t(err) else { hasStandby = true; standbyMsg = tr("Standby payload saved in the app.", "حُفظ payload الإطفاء داخل التطبيق.") }
        }
    }
    var scanning by remember { mutableStateOf(false) }
    var scanDone by remember { mutableStateOf(false) }
    var scanNoNet by remember { mutableStateOf(false) }
    var scanProg by remember { mutableFloatStateOf(0f) }
    val found = remember { mutableStateListOf<Discovery.Found>() }
    fun scan() {
        if (scanning) return
        scanning = true; scanDone = false; scanNoNet = false; scanProg = 0f; found.clear()
        scope.launch {
            val n = Discovery.scan({ d, t -> scanProg = d.toFloat() / t }, { f -> scope.launch { found.add(f) } })
            scanNoNet = n == 0; scanning = false; scanDone = true
            // exactly one strong candidate and nothing typed yet: fill it in
            val strong = found.filter { it.likely }
            if (host.isBlank() && strong.size == 1) host = strong[0].host
        }
    }
    LaunchedEffect(Unit) { if (isNew && host.isBlank()) scan() }
    fun build() = Ps4(id = existing?.id ?: java.util.UUID.randomUUID().toString(), name = name.trim().ifBlank { "PS4" }, host = host.trim(), dest = DownloadMonitor.norm(dest), token = token.trim(), wakeKey = wakeKey.trim(), autoWake = autoWake && wakeKey.isNotBlank())
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BackHeader(if (isNew) tr("Add PS4", "إضافة PS4") else tr("Edit PS4", "تعديل PS4"), nav)
        val full = Modifier.fillMaxWidth()
        OutlinedTextField(name, { name = it }, label = { Text(tr("Name (e.g. Living Room)", "الاسم (مثل غرفة المعيشة)")) }, singleLine = true, modifier = full)
        Panel {
            Button(enabled = !scanning, onClick = { scan() }, modifier = full) { Ico(R.drawable.ic_search, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Find my PS4 on the network", "البحث عن الـPS4 في الشبكة")) }
            if (scanning) { LinearProgressIndicator(progress = { scanProg.coerceIn(0f, 1f) }, modifier = full); Dim(tr("Searching… (a few seconds)", "جارٍ البحث… (بضع ثوانٍ)")) }
            found.sortedByDescending { it.likely }.forEach { f ->
                Card(onClick = { host = f.host }, modifier = full, colors = CardDefaults.cardColors(containerColor = if (f.host == host) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(f.host + if (f.likely) "   ✓" else "", fontWeight = FontWeight.SemiBold)
                        Dim((if (f.server) tr("PS4 server (", "خادم PS4 (") + f.serverKind + "), " else "") + (if (f.loader) tr("GoldHEN loader, ", "محمّل GoldHEN، ") else "") + tr("open ports: ", "المنافذ المفتوحة: ") + f.ports)
                    }
                }
            }
            if (scanDone && scanNoNet) Dim(tr("The phone has no local network address. Connect to the same Wi-Fi as the PS4.", "ليس للهاتف عنوان شبكة محلية. اتصل بنفس شبكة الواي فاي الخاصة بالـPS4."))
            else if (scanDone && found.isEmpty()) Dim(tr("No PS4 found. Make sure it is on (not in rest mode), on the same Wi-Fi, and that PS4 server or GoldHEN's payload loader is running. Or type the IP below.", "لم يُعثر على PS4. تأكد أنه يعمل (ليس في وضع الراحة) وعلى نفس الشبكة، وأن PS4 server أو محمّل GoldHEN يعمل. أو اكتب الـIP بالأسفل."))
        }
        OutlinedTextField(host, { host = it }, label = { Text(tr("PS4 IP address / host", "عنوان IP للـPS4")) }, singleLine = true, modifier = full, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(dest, { dest = it }, label = { Text(tr("Default download folder", "مجلد التحميل الافتراضي")) }, singleLine = true, modifier = full)
        OutlinedTextField(token, { token = it.filter { c -> c.code in 33..126 } }, label = { Text(tr("Server token (only if you set one on the server)", "رمز الخادم (فقط إن وضعت رمزًا على الخادم)")) }, singleLine = true, modifier = full)
        Panel {
            Text(tr("Rest mode", "وضع الراحة"), fontWeight = FontWeight.SemiBold)
            Dim(tr("The app already shows whether the PS4 is on or in rest mode. To also turn it on from the app, paste its wake key (user-credential of a registered Remote Play client). Optional.",
                "التطبيق يعرض أصلاً هل الـPS4 يعمل أو في وضع الراحة. لتشغيله من التطبيق أيضاً، الصق مفتاح التشغيل (user-credential لعميل Remote Play مسجَّل). اختياري."))
            OutlinedTextField(wakeKey, { wakeKey = it.filter { c -> c.code in 33..126 } }, label = { Text(tr("Wake key (optional)", "مفتاح التشغيل (اختياري)")) }, singleLine = true, modifier = full)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(autoWake && wakeKey.isNotBlank(), { autoWake = it }, enabled = wakeKey.isNotBlank())
                Text(tr("Wake the PS4 by itself when it is in rest mode and a download is unfinished", "أيقظ الـPS4 تلقائياً إن كان في وضع الراحة وهناك تحميل غير مكتمل"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Panel {
            Text(tr("Turn off after downloads", "الإطفاء بعد التحميل"), fontWeight = FontWeight.SemiBold)
            Dim(tr("The download switch \"Turn the PS4 off when the download finishes\" sends a small payload (standby.elf) to GoldHEN's payload loader (port 9090), which puts the PS4 in rest mode. Turn on \"Enable PayLoader Server\" in GoldHEN's settings. Build standby.elf from payload/standby in the project.",
                "مفتاح التحميل «إطفاء الـPS4 بعد انتهاء التحميل» يرسل payload صغيراً (standby.elf) إلى محمّل الـpayload في GoldHEN (المنفذ 9090) فيُدخل الـPS4 في وضع الراحة. فعّل «Enable PayLoader Server» في إعدادات GoldHEN. ابنِ standby.elf من المجلد payload/standby في المشروع."))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(fullOff, { fullOff = it; Standby.setFullOff(it); hasStandby = Standby.available(ctx) })
                Text(tr("Turn the PS4 off completely (not rest mode)", "أطفئ الـPS4 كليًا (وليس وضع الراحة)"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            if (fullOff) Dim(tr("After a full power-off the PS4 cannot be woken from the app; press its power button. Downloads stop. Uses poweroff.elf, which calls sceSystemStateMgrTurnOff; that function name is NOT verified on a console.", "بعد الإطفاء الكلي لا يستطيع التطبيق إيقاظ الـPS4؛ اضغط زر الطاقة فيه. تتوقف التحميلات. يستعمل poweroff.elf الذي يستدعي sceSystemStateMgrTurnOff؛ اسم هذه الدالة لم يُتحقَّق منه على جهاز."))
            Text(if (hasStandby) tr("Payload: ready", "الـpayload: جاهز") else tr("Payload: not available", "الـpayload: غير متوفر"), style = MaterialTheme.typography.bodyMedium)
            if (standbyMsg.isNotEmpty()) Text(standbyMsg, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { standbyPicker.launch(arrayOf("*/*")) }) { Lbl(tr("Choose standby.elf", "اختيار standby.elf")) }
                OutlinedButton(enabled = hasStandby && host.isNotBlank() && !standbyBusy, onClick = {
                    standbyBusy = true; standbyMsg = if (fullOff) tr("Sending… the PS4 will turn off completely if it works.", "جارٍ الإرسال… سينطفئ الـPS4 كليًا إن نجح.") else tr("Sending… the PS4 will go to rest mode if it works.", "جارٍ الإرسال… سيدخل الـPS4 وضع الراحة إن نجح.")
                    scope.launch { standbyMsg = Standby.message(Standby.enter(ctx, host.trim())); standbyBusy = false }
                }) { Lbl(tr("Test: turn off now", "تجربة: أطفئ الآن")) }
            }
        }
        if (busy) LinearProgressIndicator(full)
        if (result.isNotEmpty()) Text(Tx.lines(result), fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy && host.isNotBlank(), onClick = { busy = true; result = ""; scope.launch { result = DownloadMonitor.probe(build()); busy = false } }) { Lbl(tr("Test connection", "اختبار الاتصال")) }
            Button(enabled = host.isNotBlank(), onClick = { val np = build(); Ps4Repo.save(np); Ps4Server.forget(np); nav.popBackStack() }) { Lbl(tr("Save", "حفظ")) }
        }
        if (!isNew) {
            val inUse = DownloadRepo.all.value.any { it.ps4Id == existing!!.id && it.state.active }
            OutlinedButton(enabled = !inUse, onClick = { Ps4Repo.delete(existing!!.id); nav.popBackStack() }) { Lbl(tr("Delete this PS4", "حذف هذا الجهاز"), color = MaterialTheme.colorScheme.error) }
            if (inUse) Dim(tr("Cannot delete while downloads are active on this PS4.", "لا يمكن الحذف أثناء وجود تحميلات نشطة على هذا الجهاز."))
        }
    }
}
