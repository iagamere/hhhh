package com.abdo.ps4monitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import android.content.Context
import android.net.Uri
import java.io.File

/** What the console itself says about its power state (DDP, UDP port 987). */
enum class Power(private val en: String, private val ar: String) {
    UNKNOWN("Not checked", "لم يُفحص"), ON("On", "يعمل"), REST("Rest mode", "وضع الراحة"), OFF("No answer", "لا يستجيب");
    val label get() = tr(en, ar)
}

/**
 * PS4 "device discovery protocol" (DDP). It is answered by the console's system software itself, so it works in rest mode
 * (when "Stay Connected to the Internet" / "Enable Turning On PS4 from Network" are on in the PS4's Power Save Settings):
 *   request  "SRCH * HTTP/1.1"  ->  "HTTP/1.1 200 Ok" (on)  or  "HTTP/1.1 620 Server Standby" (rest mode)
 * Waking needs the console's registration key ("user-credential") from a registered Remote Play / Second Screen client.
 * NOT tested on a real console by the author.
 */
object Ps4Power {
    private const val PORT = 987
    private const val VERSION = "00020020"

    /** One DDP question. Two tries, because a single UDP packet can be lost on Wi-Fi. */
    suspend fun probe(host: String, timeoutMs: Int = 1200): Power = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext Power.UNKNOWN
        repeat(2) {
            val r = askOnce(host, "SRCH * HTTP/1.1\ndevice-discovery-protocol-version:$VERSION\n", timeoutMs)
            if (r != null) {
                val code = Regex("^HTTP/1\\.1\\s+(\\d{3})").find(r.trimStart())?.groupValues?.get(1)
                return@withContext when (code) { "200" -> Power.ON; "620" -> Power.REST; else -> Power.UNKNOWN }
            }
        }
        Power.OFF
    }

    private fun askOnce(host: String, msg: String, timeoutMs: Int): String? = try {
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            val data = msg.toByteArray()
            s.send(DatagramPacket(data, data.size, InetAddress.getByName(host), PORT))
            val buf = ByteArray(1024); val pk = DatagramPacket(buf, buf.size)
            s.receive(pk); String(pk.data, 0, pk.length)
        }
    } catch (e: SocketTimeoutException) { null } catch (e: Exception) { null }

    /** Sends the wake-up packet (3 times) and then watches for up to [waitMs] until the console says it is on. Returns null on success, else a short reason. */
    suspend fun wake(host: String, credential: String, waitMs: Long = 25_000L): String? = withContext(Dispatchers.IO) {
        val cred = credential.trim()
        if (host.isBlank()) return@withContext "no-host"
        if (cred.isEmpty()) return@withContext "no-credential"
        val msg = "WAKEUP * HTTP/1.1\nclient-type:a\nauth-type:C\nuser-credential:$cred\ndevice-discovery-protocol-version:$VERSION\n"
        try {
            DatagramSocket().use { s ->
                val data = msg.toByteArray(); val ip = InetAddress.getByName(host)
                repeat(3) { s.send(DatagramPacket(data, data.size, ip, PORT)); delay(300) }
            }
        } catch (e: Exception) { return@withContext "send-failed: ${e.javaClass.simpleName}" }
        val end = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < end) {
            delay(2000)
            if (probe(host, 800) == Power.ON) return@withContext null
        }
        "no-response"
    }

    fun wakeMessage(err: String?): String = when {
        err == null -> tr("The PS4 woke up.", "استيقظ الـPS4.")
        err == "no-credential" -> tr("Enter the PS4's wake key in this PS4's settings first.", "أدخل مفتاح التشغيل الخاص بالـPS4 في إعداداته أولاً.")
        err == "no-host" -> tr("This PS4 has no IP address.", "لا يوجد عنوان IP لهذا الـPS4.")
        err.startsWith("send-failed") -> tr("Could not send the wake packet ($err).", "تعذّر إرسال حزمة التشغيل ($err).")
        else -> tr("Wake packet sent but the PS4 did not turn on. Check the key, and on the PS4: Settings > Power Save Settings > Set Features Available in Rest Mode > turn on \"Stay Connected to the Internet\" and \"Enable Turning on PS4 from Network\".",
            "أُرسلت حزمة التشغيل لكن الـPS4 لم يعمل. تحقق من المفتاح، وفي الـPS4: الإعدادات > إعدادات توفير الطاقة > تحديد الميزات المتاحة في وضع الراحة > فعّل «البقاء متصلاً بالإنترنت» و«تمكين تشغيل PS4 من الشبكة».")
    }
}

/**
 * Turning the PS4 off. Neither DDP nor our download server (port 6701) has a "go to rest mode" command, so the app sends a tiny payload
 * (assets/standby.elf, or the file chosen in the PS4's settings) to GoldHEN's payload loader (TCP 9090, the same way the server is sent).
 * The payload only asks the system to enter rest mode (see payload/standby/standby.c). NOT tested on a real console by the author.
 */
object Standby {
    fun file(ctx: Context) = File(ctx.filesDir, "standby.elf")
    private fun bundled(ctx: Context, name: String = "standby.elf"): Boolean = try { ctx.assets.open(name).use { true } } catch (e: Exception) { false }
    /** true = fully power the console off (poweroff.elf, calls sceSystemStateMgrTurnOff); false = rest mode (standby.elf). Saved in the app. */
    fun fullOff(): Boolean = try { Store.sp.getBoolean("power_full_off", false) } catch (e: Exception) { false }
    fun setFullOff(on: Boolean) { try { Store.sp.edit().putBoolean("power_full_off", on).apply() } catch (e: Exception) { } }
    /** True when there is a payload to send (bundled in the app, or imported for rest mode). */
    fun available(ctx: Context, off: Boolean = fullOff()): Boolean = if (off) bundled(ctx, "poweroff.elf") else (bundled(ctx) || file(ctx).length() > 1024)

    /** Copies a chosen file into the app. Returns an error text, or null when saved. */
    fun import(ctx: Context, uri: Uri): String? {
        try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return "Could not read the file."
            if (bytes.size < 1024 || bytes.size > 4 * 1024 * 1024) return "This does not look like the standby payload (unexpected size)."
            if (!(bytes[0].toInt() == 0x7F && bytes[1].toInt() == 0x45 && bytes[2].toInt() == 0x4C && bytes[3].toInt() == 0x46)) return "This is not an ELF file."
            file(ctx).writeBytes(bytes)
            return null
        } catch (e: Exception) { return Ps4Api.friendly(e) }
    }

    /** Sends the payload, then watches the console. Rest mode: success = DDP says "rest mode" (or the console stops answering) within [waitMs].
     *  Full power-off: success = the console stops answering twice in a row. Null = success, else a short reason. */
    suspend fun enter(ctx: Context, host: String, waitMs: Long = 40_000L, off: Boolean = fullOff()): String? = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext "no-host"
        if (!available(ctx, off)) return@withContext "no-payload"
        val f = File(ctx.cacheDir, "standby-send.elf")
        try {
            if (!off && file(ctx).length() > 1024) file(ctx).copyTo(f, overwrite = true)
            else ctx.assets.open(if (off) "poweroff.elf" else "standby.elf").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        } catch (e: Exception) { return@withContext "payload-unreadable" }
        val err = ServerLoader.send(host, f) { }
        runCatching { f.delete() }
        if (err != null) return@withContext "send-failed: $err"
        val end = System.currentTimeMillis() + waitMs
        var silent = 0; var sawRest = false
        while (System.currentTimeMillis() < end) {
            delay(2500)
            val pw = Ps4Power.probe(host, 800)
            if (!off) { if (pw == Power.REST || pw == Power.OFF) return@withContext null }
            else {
                if (pw == Power.OFF) { silent++; if (silent >= 2) return@withContext null } else silent = 0
                if (pw == Power.REST) sawRest = true
            }
        }
        if (off && sawRest) "rest-instead" else "still-on"
    }

    fun message(err: String?, off: Boolean = fullOff()): String = when {
        err == null -> if (off) tr("The PS4 turned off completely. It can only be turned on again with its power button (the app cannot wake a fully off console).", "انطفأ الـPS4 كليًا. لا يُشغَّل إلا بزر الطاقة فيه (التطبيق لا يستطيع إيقاظ جهاز مطفأ كليًا).")
                       else tr("The PS4 went to rest mode.", "دخل الـPS4 في وضع الراحة.")
        err == "no-payload" -> if (off) tr("poweroff.elf is not inside this app.", "ملف poweroff.elf غير موجود داخل التطبيق.")
                               else tr("No standby payload in the app. Choose standby.elf in this PS4's settings.", "لا يوجد payload الإطفاء في التطبيق. اختر standby.elf من إعدادات هذا الـPS4.")
        err == "no-host" -> tr("This PS4 has no IP address.", "لا يوجد عنوان IP لهذا الـPS4.")
        err == "payload-unreadable" -> tr("Could not read the payload.", "تعذّرت قراءة الـpayload.")
        err == "rest-instead" -> tr("The PS4 went to rest mode, not fully off. This firmware may not support the full power-off call.", "دخل الـPS4 وضع الراحة ولم ينطفئ كليًا. قد لا يدعم هذا الإصدار استدعاء الإطفاء الكلي.")
        err.startsWith("send-failed") -> tr("Could not send the payload to the PS4 (GoldHEN's payload loader, port 9090, must be on): ", "تعذّر إرسال payload إلى الـPS4 (يجب تفعيل محمّل الـpayload في GoldHEN، المنفذ 9090): ") + err.removePrefix("send-failed: ")
        else -> if (off) tr("The payload was sent but the PS4 is still on. The function name in it was not verified on a console.", "أُرسل payload لكن الـPS4 ما زال يعمل. اسم الدالة فيه لم يُتحقَّق منه على جهاز.")
                else tr("The payload was sent but the PS4 is still on.", "أُرسل payload لكن الـPS4 ما زال يعمل.")
    }
}
