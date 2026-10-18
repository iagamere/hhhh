@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.abdo.ps4monitor
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun GamesTab(ps4: Ps4, reload: Int) {
    var res by remember(ps4.id, reload) { mutableStateOf<Pair<List<GameInfo>, List<String>>?>(null) }
    var done by remember(ps4.id, reload) { mutableIntStateOf(0) }; var total by remember(ps4.id, reload) { mutableIntStateOf(0) }
    var q by rememberSaveable { mutableStateOf("") }
    var open by remember { mutableStateOf<GameInfo?>(null) }
    LaunchedEffect(ps4.id, reload) { res = null; res = withContext(Dispatchers.IO) { Ps4Info.scanGames(ps4) { d, t -> done = d; total = t } } }
    val r = res
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (r == null) { LinearProgressIndicator(progress = { if (total > 0) done.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth()); Dim(tr("Reading installed games…", "جارٍ قراءة الألعاب المثبتة…") + if (total > 0) " $done/$total" else ""); return@Column }
        if (r.first.isEmpty()) { EmptyState(R.drawable.ic_package, tr("No installed games found.", "لم يُعثر على ألعاب مثبتة.")); NoteList(r.second); return@Column }
        OutlinedTextField(q, { q = it }, label = { Text(tr("Search", "بحث") + " (${r.first.size})") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val list = r.first.filter { q.isBlank() || it.title.contains(q, true) || it.titleId.contains(q, true) }
        LazyVerticalGrid(GridCells.Adaptive(128.dp), Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list, key = { it.titleId }) { g -> GameCard(ps4, g) { open = g } }
            item(span = { GridItemSpan(maxLineSpan) }) { NoteList(r.second) }
        }
    }
    open?.let { GameSheet(ps4, it) { open = null } }
}

/** One game: the square cover (icon0.png is 512x512, shown 1:1 so nothing is stretched or cut), then its name and id. */
@Composable private fun GameCard(ps4: Ps4, g: GameInfo, onClick: () -> Unit) {
    val paths = remember(g.titleId, g.metaDir) { Ps4Info.art(g, "icon0.png") }
    val icon by produceState<Bitmap?>(Thumbs.peekAny(ps4, paths, 256), g.titleId) { if (value == null) value = Thumbs.loadAny(ps4, paths, 256) }
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            val b = icon
            if (b != null) Image(b.asImageBitmap(), g.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Ico(R.drawable.ic_package, 36.dp, MaterialTheme.colorScheme.outline)
            val cat = Ps4Info.categoryLabel(g.category)
            if (g.category == "gp" || g.category == "ac") Surface(Modifier.align(Alignment.TopStart).padding(6.dp), shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(cat, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(g.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            Dim(listOfNotNull(g.version.takeIf { it.isNotBlank() }?.let { "v$it" }, g.titleId).joinToString("  •  "), maxLines = 1)
        }
    }
}

@Composable private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(0.4f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Details of one game: background picture (pic1/pic0), cover, name and everything param.sfo says about it. */
@Composable private fun GameSheet(ps4: Ps4, g: GameInfo, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val iconPaths = remember(g.titleId, g.metaDir) { Ps4Info.art(g, "icon0.png") }
    val bannerPaths = remember(g.titleId, g.metaDir) { Ps4Info.art(g, "pic1.png", "pic0.png") }
    val icon by produceState<Bitmap?>(Thumbs.peekAny(ps4, iconPaths, 512), g.titleId) { if (value == null) value = Thumbs.loadAny(ps4, iconPaths, 512) }
    val banner by produceState<Bitmap?>(Thumbs.peekAny(ps4, bannerPaths, 1280), g.titleId) { if (value == null) value = Thumbs.loadAny(ps4, bannerPaths, 1280) }
    val rows = buildList {
        add(tr("Title ID", "معرّف اللعبة") to g.titleId)
        g.sfo["CONTENT_ID"]?.takeIf { it.isNotBlank() }?.let { add(tr("Content ID", "معرّف المحتوى") to it) }
        Ps4Info.categoryLabel(g.category).takeIf { it.isNotBlank() }?.let { add(tr("Type", "النوع") to "$it (${g.category})") }
        g.version.takeIf { it.isNotBlank() }?.let { add(tr("Version", "الإصدار") to it) }
        g.sfo["VERSION"]?.takeIf { it.isNotBlank() && it != g.version }?.let { add(tr("Content version", "إصدار المحتوى") to it) }
        Ps4Info.firmware(g.sfo["SYSTEM_VER"])?.let { add(tr("Required firmware", "النظام المطلوب") to it) }
        g.sfo["PARENTAL_LEVEL"]?.takeIf { it.isNotBlank() }?.let { add(tr("Parental level", "المستوى العائلي") to it) }
        add(tr("Install folder", "مجلد التثبيت") to "/user/app/${g.titleId}")
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                val bn = banner
                if (bn != null) {
                    Image(bn.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)))))
                } else Ico(R.drawable.ic_package, 48.dp, MaterialTheme.colorScheme.outline)
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    val ic = icon
                    if (ic != null) Image(ic.asImageBitmap(), g.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Ico(R.drawable.ic_package, 36.dp, MaterialTheme.colorScheme.outline)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(g.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Dim(listOfNotNull(g.titleId, g.version.takeIf { it.isNotBlank() }?.let { "v$it" }).joinToString("  •  "))
                }
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { (k, v) -> InfoRow(k, v) }
                FilledTonalButton(onClick = { clip.setText(AnnotatedString(g.titleId)); Toast.makeText(ctx, tr("Copied", "تم النسخ"), Toast.LENGTH_SHORT).show() }) {
                    Ico(R.drawable.ic_copy, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Copy title ID", "نسخ معرّف اللعبة"))
                }
            }
        }
    }
}
