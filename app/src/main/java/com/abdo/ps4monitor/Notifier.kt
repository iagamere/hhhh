package com.abdo.ps4monitor
import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Channels:
 *  - "dl": ONE card per download (active or paused). A custom RemoteViews layout (DecoratedCustomViewStyle, so the system keeps the app header):
 *    a card in a dark tone of the game's cover colour, the cover, the title, a REAL determinate ProgressBar whose fill is the cover colour,
 *    percentage, downloaded / total, size left, time left, speed, and two buttons: Pause <-> Resume, and Delete (asks for a second tap).
 *    Android does not colour a normal notification's background (setColorized only works for foreground-service notifications, or media
 *    notifications with a MediaSession), so the colour lives inside the card, which the app fully controls.
 *  - "monitor_min": the mandatory foreground-service notification (the one place where setColorized is honoured): one quiet line with the
 *    overall progress; colourized with the cover colour while exactly one download is active.
 *  - "alerts": a heads-up alert when a download has been interrupted for a while (one per incident, removed when it recovers).
 *  - "done": one notification per finished / failed download, grouped.
 * There is no per-download progress notification any more (that is what flooded the shade).
 */
object Notifier {
    private lateinit var ctx: Context
    private var lastSig = ""
    private val troubleSince = ConcurrentHashMap<Int, Long>()
    private val alerted = ConcurrentHashMap<Int, Long>()
    private const val ALERT_BASE = 500_000
    const val ACT_PAUSE = "com.abdo.ps4monitor.NOTIF_PAUSE"
    const val ACT_RESUME = "com.abdo.ps4monitor.NOTIF_RESUME"
    const val ACT_DELETE = "com.abdo.ps4monitor.NOTIF_DELETE"
    private const val CONFIRM_MS = 6_000L
    private val confirmDel = ConcurrentHashMap<String, Long>()     // download id -> time until which the next Delete tap is the confirmation
    private val posted = ConcurrentHashMap<Int, String>()          // notification id -> signature of the card currently shown

    fun init(c: Context) {
        ctx = c.applicationContext
        val m = ctx.getSystemService(NotificationManager::class.java)
        runCatching { m.deleteNotificationChannel("active"); m.deleteNotificationChannel("results") }     // channels of the old version
        runCatching { m.deleteNotificationChannel("monitor") }     // channel of 4.2 (its notification was the big combined one)
        m.createNotificationChannel(NotificationChannel("dl", tr("Downloads", "التحميلات"), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        m.createNotificationChannel(NotificationChannel("monitor_min", tr("Monitoring (service)", "المراقبة (الخدمة)"), NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        m.createNotificationChannel(NotificationChannel("alerts", tr("Interruptions", "انقطاع التحميل"), NotificationManager.IMPORTANCE_HIGH))
        m.createNotificationChannel(NotificationChannel("done", tr("Finished downloads", "التحميلات المنتهية"), NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun pi() = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
    private fun psName(d: Download) = Ps4Repo.get(d.ps4Id)?.name.orEmpty()
    private fun gameTitle(d: Download) = d.pkgTitle ?: d.displayName
    private fun cover(d: Download) = if (d.iconReady) PkgStore.bitmap("d:${d.id}", "icon0.png", 128) else null

    private fun activeList() = DownloadRepo.all.value.filter { it.state.active && it.state != DlState.SUBMITTING && !it.superseded }

    private fun oneLine(d: Download): String {
        val s = when (d.state) {
            DlState.DOWNLOADING -> listOfNotNull(d.pct?.let { "$it%" }, Fmt.mbs(d.speed)).joinToString(" • ")
            DlState.STALLED -> tr("stalled", "متعثّر")
            DlState.SERVER_DOWN -> tr("server not reachable", "الخادم غير متاح")
            DlState.QUEUED -> tr("queued", "في الانتظار")
            else -> d.state.label
        }
        return "${gameTitle(d).take(34)} — $s"
    }

    /** The mandatory foreground-service notification (id 1): one quiet line. The per-download cards carry the details. */
    fun summary(c: Context, fallback: String): Notification {
        val act = activeList()
        val b = NotificationCompat.Builder(c, "monitor_min").setSmallIcon(R.drawable.ic_stat_ps4).setOngoing(true).setOnlyAlertOnce(true)
            .setShowWhen(false).setContentIntent(pi()).setPriority(NotificationCompat.PRIORITY_MIN).setContentTitle("PS4 Monitor")
        if (act.isEmpty() || !Store.settings().notif) return b.setContentText(fallback).build()
        val speed = act.filter { it.state == DlState.DOWNLOADING }.sumOf { it.speed }
        val trouble = act.any { it.state == DlState.STALLED || it.state == DlState.SERVER_DOWN }
        val head = if (act.size == 1) tr("1 download", "تحميل واحد") else tr("${act.size} downloads", "${act.size} تحميلات")
        val line = listOfNotNull(head, if (speed > 0) Fmt.mbs(speed) else null, if (trouble) tr("interrupted - retrying", "منقطع - تجري إعادة المحاولة") else null).joinToString(" • ")
        // overall progress as the system's own (natively tinted) progress bar
        val known = act.filter { (it.expectedSize ?: 0L) > 0L }
        val total = known.sumOf { it.expectedSize ?: 0L }
        if (total > 0L) b.setProgress(1000, (known.sumOf { minOf(it.currentSize, it.expectedSize ?: 0L) } * 1000L / total).toInt().coerceIn(0, 1000), false)
        // colour: the downloading card if there is one. Colourized only for a single download (with several, one colour would be arbitrary).
        val lead = act.firstOrNull { it.state == DlState.DOWNLOADING } ?: act.first()
        val pal = palette(lead)
        b.setColor(if (act.size == 1) pal.surface else pal.accent)
        if (act.size == 1) b.setColorized(true)       // honoured here because this notification is the foreground-service one
        return b.setContentText(line).build()
    }
    /** Re-posts the ongoing notification only when something visible changed. */
    fun refreshSummary(c: Context, fallback: String) {
        val sig = activeList().joinToString(";") { "${it.id}:${it.state}:${it.pct}:${(it.speed / 262144).toInt()}:${it.etaSec / 20}:${it.iconReady}" } + "|" + fallback + "|" + Store.settings().notif
        if (sig == lastSig) return
        lastSig = sig
        post(1, summary(c, fallback))
    }

    /** Call for every active download on each poll: raises ONE alert when the trouble lasted long enough. */
    fun watchTrouble(d: Download) {
        if (!Store.sp.getBoolean("alert_int", true) || !Store.settings().notif) return
        val id = d.notificationId; val now = System.currentTimeMillis()
        val bad = d.state == DlState.STALLED || d.state == DlState.SERVER_DOWN
        if (!bad) { troubleSince.remove(id); if (alerted.remove(id) != null) cancelAlert(id); return }
        val since = troubleSince.getOrPut(id) { now }
        val limit = if (d.state == DlState.SERVER_DOWN) 90_000L else 180_000L
        if (now - since < limit) return
        val last = alerted[id]
        if (last != null && now - last < 30 * 60_000L) return          // remind at most every 30 minutes
        alerted[id] = now
        post(id + ALERT_BASE, alertNotification(d, now - since))
    }
    private fun alertNotification(d: Download, trouble: Long): Notification {
        val mins = (trouble / 60_000L).coerceAtLeast(1L)
        val why = if (d.state == DlState.SERVER_DOWN)
            tr("ezRemote Server on the PS4 is not answering (the PS4 may be asleep, or the server stopped).", "خادم ezRemote على الـPS4 لا يستجيب (قد يكون الجهاز في وضع الراحة أو توقف الخادم).")
        else tr("No download progress is being made.", "لا يوجد تقدّم في التحميل.")
        val at = listOfNotNull(d.pct?.let { "$it%" }, if (d.currentSize > 0) Fmt.gb(d.currentSize) else null).joinToString(" • ")
        val body = why + "\n" + tr("Interrupted for $mins min", "منقطع منذ $mins د") + (if (at.isNotEmpty()) " • $at" else "") + "\n" + tr("Tap to open the app.", "اضغط لفتح التطبيق.")
        val b = NotificationCompat.Builder(ctx, "alerts").setSmallIcon(R.drawable.ic_stat_ps4)
            .setContentTitle(tr("Download interrupted", "انقطع التحميل") + ": " + gameTitle(d)).setContentText(why)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setSubText(psName(d).ifEmpty { null })
            .setAutoCancel(true).setContentIntent(pi()).setCategory(NotificationCompat.CATEGORY_ERROR).setPriority(NotificationCompat.PRIORITY_HIGH)
        cover(d)?.let { b.setLargeIcon(it) }
        CoverColor.forDownload(d)?.let { b.setColor(it) }
        return b.build()
    }

    /** Completed / failed / not started: posted once (guarded by Download.terminalNotified), grouped. */
    fun result(d: Download) {
        clearTrouble(d.notificationId)
        if (!Store.settings().notif) return
        val ok = d.state == DlState.COMPLETED
        val secs = if (d.startedAt > 0 && d.completedAt > d.startedAt) (d.completedAt - d.startedAt) / 1000L else 0L
        val head = when {
            ok -> tr("Download completed", "اكتمل التحميل")
            d.state == DlState.FAILED -> tr("Download failed", "فشل التحميل")
            else -> tr("Download has not started yet", "لم يبدأ التحميل بعد")
        }
        val body = when {
            ok -> listOfNotNull(Fmt.gb(d.currentSize), if (secs > 0) tr("in ${Fmt.dur(secs)}", "خلال ${Fmt.dur(secs)}") else null, tr("verified", "تم التحقق")).joinToString(" • ")
            d.state == DlState.FAILED -> Tx.t(d.errorMessage ?: tr("Unknown reason", "سبب غير معروف"))
            else -> tr("ezRemote accepted the request but no download activity was detected.", "قبل ezRemote الطلب لكن لم يُكتشف أي نشاط تحميل.")
        }
        val b = NotificationCompat.Builder(ctx, "done").setSmallIcon(R.drawable.ic_stat_ps4).setGroup("ps4_done")
            .setContentTitle(gameTitle(d)).setContentText("$head • $body").setSubText(psName(d).ifEmpty { null })
            .setStyle(NotificationCompat.BigTextStyle().bigText("$head\n$body")).setAutoCancel(true).setContentIntent(pi())
        cover(d)?.let { b.setLargeIcon(it) }
        CoverColor.forDownload(d)?.let { b.setColor(it) }
        post(d.notificationId, b.build())
        post(2, NotificationCompat.Builder(ctx, "done").setSmallIcon(R.drawable.ic_stat_ps4).setGroup("ps4_done").setGroupSummary(true)
            .setContentTitle(tr("PS4 downloads", "تحميلات PS4")).setAutoCancel(true).setContentIntent(pi()).build())
    }

    private fun cancelAlert(id: Int) { runCatching { NotificationManagerCompat.from(ctx).cancel(id + ALERT_BASE) } }
    fun clearTrouble(id: Int) { troubleSince.remove(id); if (alerted.remove(id) != null) cancelAlert(id) }
    fun cancel(id: Int) { clearTrouble(id); posted.remove(id); runCatching { NotificationManagerCompat.from(ctx).cancel(id) } }

    // ---------------------------------------------------------------------------------------------
    // Download cards (music-player style)
    // ---------------------------------------------------------------------------------------------
    private fun cardWanted(d: Download) = !d.superseded && d.state != DlState.SUBMITTING && (d.state.active || d.state == DlState.PAUSED)
    private fun confirming(id: String) = (confirmDel[id] ?: 0L) > System.currentTimeMillis()

    /** No cover yet: a stable colour taken from the file name, so the card is still coloured. */
    private fun fallbackColor(d: Download): Int =
        Color.HSVToColor(floatArrayOf(((d.displayName.hashCode() and 0x7fffffff) % 360).toFloat(), 0.55f, 0.55f))

    /** Colours of this download's card (cover colour, cached in [CoverColor]; no bitmap is decoded again for an already known cover). */
    private fun palette(d: Download): CoverColor.Palette = CoverColor.palette(CoverColor.forDownload(d) ?: fallbackColor(d))

    private const val ON_SURFACE = -0x1                     // 0xFFFFFFFF
    private const val ON_SURFACE_VAR = 0xB3FFFFFF.toInt()   // 70 % white
    private const val WARN = 0xFFFFB74D.toInt()             // amber: "confirm delete"

    private class CardText(val pct: String?, val size: String, val left: String, val speed: String?, val sub: String) {
        /** one line for the collapsed card (and the plain content text) */
        val line get() = listOfNotNull(pct, size).joinToString(" • ")
    }

    /** "53.72 / 64.92 GB" when both values have the same unit, "953.20 MB / 64.92 GB" otherwise */
    private fun sizePair(cur: Long, total: Long): String {
        val a = Fmt.bytes(cur); val b = Fmt.bytes(total)
        return if (a.substringAfterLast(' ') == b.substringAfterLast(' ')) a.substringBeforeLast(' ') + " / " + b else "$a / $b"
    }

    private fun cardTexts(d: Download): CardText {
        val total = d.expectedSize ?: 0L
        val left = if (total > 0L) (total - d.currentSize).coerceAtLeast(0L) else 0L
        val size = if (total > 0L) sizePair(d.currentSize, total) else Fmt.bytes(d.currentSize)
        val leftTxt = if (left > 0L) "${Fmt.bytes(left)} ${tr("left", "متبقٍ")}" else null
        val speed = if (d.state == DlState.DOWNLOADING && d.speed > 0) Fmt.mbs(d.speed) else null
        val leftLine = when (d.state) {
            DlState.DOWNLOADING -> listOfNotNull(leftTxt, if (d.etaSec >= 0) Fmt.dur(d.etaSec) else null).joinToString(" • ")
            DlState.PAUSED -> listOfNotNull(tr("Paused", "متوقف مؤقتًا"), leftTxt).joinToString(" • ")
            else -> listOfNotNull(d.state.label, leftTxt).joinToString(" • ")
        }
        // same text as before in the notification header: remaining size, time left and speed
        val sub = listOfNotNull(leftLine.ifEmpty { null }, speed).joinToString(" • ")
        return CardText(d.pct?.let { "$it%" }, size, leftLine, speed, sub)
    }

    private fun actionPi(act: String, d: Download): PendingIntent {
        // PendingIntents are told apart by action / data, not by extras: the id goes into the data so two downloads never share one
        val i = Intent(ctx, NotifActionReceiver::class.java).setAction(act).setData(Uri.parse("ps4mon://download/${d.id}/$act")).putExtra("id", d.id)
        return PendingIntent.getBroadcast(ctx, d.notificationId * 4 + (if (act == ACT_DELETE) 1 else 0), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    // ---- cover bitmap: square, rounded, made once per download / state of the cover and kept in a small cache ----
    private val coverCache = LruCache<String, Bitmap>(16)
    private const val COVER_PX = 128

    private fun coverBitmap(d: Download, pal: CoverColor.Palette): Bitmap {
        val key = "${d.id}:${d.iconReady}"
        coverCache.get(key)?.let { return it }
        val src = cover(d)
        val out = Bitmap.createBitmap(COVER_PX, COVER_PX, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = COVER_PX * 0.2f
        val all = RectF(0f, 0f, COVER_PX.toFloat(), COVER_PX.toFloat())
        if (src != null && src.width > 0 && src.height > 0) {
            c.drawRoundRect(all, r, r, p)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            val s = minOf(src.width, src.height)
            c.drawBitmap(src, Rect((src.width - s) / 2, (src.height - s) / 2, (src.width + s) / 2, (src.height + s) / 2), Rect(0, 0, COVER_PX, COVER_PX), p)
        } else {
            // missing / unreadable cover: a tinted tile with the download glyph
            p.color = pal.button; c.drawRoundRect(all, r, r, p)
            ContextCompat.getDrawable(ctx, R.drawable.ic_download)?.mutate()?.let { g ->
                val m = COVER_PX / 4
                g.setBounds(m, m, COVER_PX - m, COVER_PX - m); g.setTint(pal.accent); g.draw(c)
            }
        }
        // cached per download; the key changes when iconReady flips, so a placeholder is replaced by the real cover
        coverCache.put(key, out)
        return out
    }
    fun forgetCover(id: String) { coverCache.remove("$id:true"); coverCache.remove("$id:false") }

    /** Fill and track of the real ProgressBar in the cover colour. See res/drawable/notif_progress.xml for what happens below Android 12. */
    private fun styleBar(rv: RemoteViews, d: Download, pal: CoverColor.Palette) {
        val indeterminate = d.frac == null && d.state == DlState.DOWNLOADING      // total size not known yet
        rv.setProgressBar(R.id.bar, 1000, ((d.frac ?: 0f) * 1000f).toInt().coerceIn(0, 1000), indeterminate)
        if (Build.VERSION.SDK_INT >= 31) {      // RemoteViews.setColorStateList: API 31. ProgressBar.setProgressTintList & co. are @RemotableViewMethod.
            val fill = ColorStateList.valueOf(if (d.state == DlState.PAUSED) pal.dim else pal.accent)
            rv.setColorStateList(R.id.bar, "setProgressTintList", fill)
            rv.setColorStateList(R.id.bar, "setProgressBackgroundTintList", ColorStateList.valueOf(pal.track))
            rv.setColorStateList(R.id.bar, "setIndeterminateTintList", fill)
        }
    }

    private fun collapsedView(d: Download, pal: CoverColor.Palette, t: CardText, paused: Boolean, confirm: Boolean): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.notif_dl_collapsed)
        rv.setInt(R.id.bg, "setColorFilter", pal.surface)
        rv.setImageViewBitmap(R.id.cover, coverBitmap(d, pal))
        rv.setTextViewText(R.id.title, gameTitle(d)); rv.setTextColor(R.id.title, ON_SURFACE)
        rv.setTextViewText(R.id.stats, if (confirm) tr("Tap again to delete download + file", "اضغط مرة أخرى لحذف التحميل وملفه") else t.line)
        rv.setTextColor(R.id.stats, if (confirm) WARN else ON_SURFACE_VAR)
        styleBar(rv, d, pal)
        val toggle = if (paused) tr("Resume", "استئناف") else tr("Pause", "إيقاف مؤقت")
        val del = if (confirm) tr("Confirm delete", "تأكيد الحذف") else tr("Delete", "حذف")
        rv.setImageViewResource(R.id.ib_toggle, if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        rv.setInt(R.id.ib_toggle, "setColorFilter", ON_SURFACE)
        rv.setContentDescription(R.id.ib_toggle, toggle)
        rv.setOnClickPendingIntent(R.id.ib_toggle, actionPi(if (paused) ACT_RESUME else ACT_PAUSE, d))
        rv.setImageViewResource(R.id.ib_delete, if (confirm) R.drawable.ic_warning else R.drawable.ic_delete)
        rv.setInt(R.id.ib_delete, "setColorFilter", if (confirm) WARN else ON_SURFACE_VAR)
        rv.setContentDescription(R.id.ib_delete, del)
        rv.setOnClickPendingIntent(R.id.ib_delete, actionPi(ACT_DELETE, d))
        return rv
    }

    private fun expandedView(d: Download, pal: CoverColor.Palette, t: CardText, paused: Boolean, confirm: Boolean): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.notif_dl_expanded)
        rv.setInt(R.id.bg, "setColorFilter", pal.surface)
        rv.setImageViewBitmap(R.id.cover, coverBitmap(d, pal))
        rv.setTextViewText(R.id.title, gameTitle(d)); rv.setTextColor(R.id.title, ON_SURFACE)
        rv.setTextViewText(R.id.state, listOfNotNull(psName(d).ifEmpty { null }, if (d.state == DlState.DOWNLOADING) tr("Downloading", "جارٍ التحميل") else if (paused) tr("Paused", "متوقف مؤقتًا") else d.state.label).joinToString(" • "))
        styleBar(rv, d, pal)
        rv.setViewVisibility(R.id.pct, if (t.pct != null) View.VISIBLE else View.GONE)
        rv.setTextViewText(R.id.pct, t.pct ?: ""); rv.setTextColor(R.id.pct, pal.accent)
        rv.setTextViewText(R.id.size, t.size)
        rv.setTextViewText(R.id.left, t.left)
        rv.setTextViewText(R.id.speed, t.speed ?: "")
        rv.setViewVisibility(R.id.hint, if (confirm) View.VISIBLE else View.GONE)
        if (confirm) rv.setTextViewText(R.id.hint, tr("Tap the bin again to delete this download and its file", "اضغط سلة المهملات مرة أخرى لحذف التحميل وملفه"))
        // buttons
        rv.setInt(R.id.btn_toggle_bg, "setColorFilter", pal.button)
        rv.setImageViewResource(R.id.btn_toggle_icon, if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        rv.setInt(R.id.btn_toggle_icon, "setColorFilter", ON_SURFACE)
        rv.setTextViewText(R.id.btn_toggle_label, if (paused) tr("Resume", "استئناف") else tr("Pause", "إيقاف مؤقت"))
        rv.setOnClickPendingIntent(R.id.btn_toggle, actionPi(if (paused) ACT_RESUME else ACT_PAUSE, d))
        rv.setInt(R.id.btn_delete_bg, "setColorFilter", if (confirm) ColorUtils.setAlphaComponent(WARN, 0x40) else pal.button)
        rv.setImageViewResource(R.id.btn_delete_icon, if (confirm) R.drawable.ic_warning else R.drawable.ic_delete)
        rv.setInt(R.id.btn_delete_icon, "setColorFilter", if (confirm) WARN else ON_SURFACE)
        rv.setTextViewText(R.id.btn_delete_label, if (confirm) tr("Confirm delete", "تأكيد الحذف") else tr("Delete", "حذف"))
        rv.setTextColor(R.id.btn_delete_label, if (confirm) WARN else ON_SURFACE)
        rv.setOnClickPendingIntent(R.id.btn_delete, actionPi(ACT_DELETE, d))
        return rv
    }

    private fun card(d: Download): Notification {
        val paused = d.state == DlState.PAUSED
        val confirm = confirming(d.id)
        val pal = palette(d)
        val t = cardTexts(d)
        val b = Notification.Builder(ctx, "dl")
            .setSmallIcon(R.drawable.ic_stat_ps4)
            // plain title / text: not drawn (the custom views are), but used by accessibility, the lock screen and watches
            .setContentTitle(gameTitle(d)).setContentText(t.line).setSubText(t.sub.ifEmpty { null })
            .setOngoing(!paused).setOnlyAlertOnce(true).setShowWhen(false).setContentIntent(pi())
            .setColor(pal.accent)       // tints the small icon / app name in the header; the background itself is not ours to colour
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(collapsedView(d, pal, t, paused, confirm))
            .setCustomBigContentView(expandedView(d, pal, t, paused, confirm))
        return b.build()
    }

    private fun cardSig(d: Download) =
        "${d.state}:${d.pct}:${d.currentSize shr 26}:${(d.speed / 262144).toInt()}:${d.etaSec / 20}:${d.iconReady}:${d.pkgTitle}:${confirming(d.id)}:${Lang.isAr}"

    /** Posts / updates / removes the cards. Cheap: a card is re-posted only when something visible changed. */
    fun refreshDownloads() {
        if (!::ctx.isInitialized) return
        val all = DownloadRepo.all.value
        if (!Store.settings().notif) { posted.keys.toList().forEach { cancel(it) }; return }
        val ids = HashSet<Int>()
        for (d in all) {
            if (!cardWanted(d)) continue
            ids += d.notificationId
            val sig = cardSig(d)
            if (posted[d.notificationId] == sig) continue
            posted[d.notificationId] = sig
            post(d.notificationId, card(d))
        }
        for (id in posted.keys.toList()) {
            if (id in ids) continue
            posted.remove(id)
            // a finished download's card was already replaced by the result notification (same id); only remove cards that have no owner
            val owner = all.firstOrNull { it.notificationId == id }
            if (owner == null || owner.superseded) runCatching { NotificationManagerCompat.from(ctx).cancel(id) }
        }
    }

    /** Buttons of a card (called by [NotifActionReceiver]). Same operations as the buttons in the app. */
    fun onAction(action: String, id: String) {
        if (!::ctx.isInitialized) return
        if (DownloadRepo.get(id) == null) return
        when (action) {
            ACT_PAUSE -> Ops.launch(tr("Pausing…", "إيقاف مؤقت…")) { DownloadMonitor.pause(id) }
            ACT_RESUME -> Ops.launch(tr("Preparing resume…", "تجهيز الاستئناف…")) { DownloadMonitor.resume(id) }
            ACT_DELETE -> {
                if (confirming(id)) {
                    confirmDel.remove(id)
                    Ops.launch(tr("Deleting…", "جارٍ الحذف…")) { DownloadMonitor.delete(id, true) }
                } else {
                    confirmDel[id] = System.currentTimeMillis() + CONFIRM_MS
                    refreshDownloads()
                    Handler(Looper.getMainLooper()).postDelayed({ refreshDownloads() }, CONFIRM_MS + 300L)
                }
            }
        }
    }

    private fun post(id: Int, n: Notification) {
        if (!::ctx.isInitialized) return
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) {}
    }
}
