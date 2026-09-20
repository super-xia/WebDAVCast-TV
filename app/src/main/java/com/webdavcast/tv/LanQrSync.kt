package com.webdavcast.tv

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import java.net.NetworkInterface
import java.net.URLDecoder

/** 取本机局域网 IPv4(优先私网段)，找不到返回 null。 */
fun lanIp(): String? {
    return try {
        val ifaces = NetworkInterface.getNetworkInterfaces()
        while (ifaces.hasMoreElements()) {
            val nif = ifaces.nextElement()
            if (!nif.isUp || nif.isLoopback) continue
            val addrs = nif.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a.isLoopbackAddress) continue
                val host = a.hostAddress
                if (host == null || host.indexOf(':') >= 0) continue
                if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) return host
            }
        }
        null
    } catch (e: Exception) { null }
}

/** 生成二维码 Bitmap；失败返回 null。 */
fun generateQr(text: String, sizePx: Int = 560): Bitmap? {
    return try {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) for (y in 0 until sizePx) {
            bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
        bmp
    } catch (e: Exception) { null }
}

/**
 * 局域网扫码填写服务器：在电视上起一个本地 HTTP 服务，手机(同一局域网)扫码后
 * 浏览器打开表单，提交后把三个字段回传到电视(回调在主线程执行)。
 */
class LanQrSync(
    private val onReceived: (bcom: String, user: String, pass: String) -> Unit,
) : NanoHTTPD(0) {   // 0 = 随机可用端口

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var received = false

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.method) {
                Method.GET -> html(FORM_HTML)
                Method.POST -> {
                    val files = mutableMapOf<String, String>()
                    try { session.parseBody(files) } catch (_: Exception) {}
                    // application/x-www-form-urlencoded 表单会被 NanoHTTPD 解析进 session.parms
                    //（而不是 files["postData"]），这里优先读 parms，读不到再兜底解析原始 body。
                    var bcom = session.parms["bcom"] ?: ""
                    var user = session.parms["user"] ?: ""
                    var pass = session.parms["pass"] ?: ""
                    if (bcom.isBlank() && user.isBlank() && pass.isBlank()) {
                        val p = parseForm(files["postData"] ?: "")
                        bcom = p["bcom"] ?: ""
                        user = p["user"] ?: ""
                        pass = p["pass"] ?: ""
                    }
                    if (!received) {
                        received = true
                        main.post { onReceived(bcom, user, pass) }
                    }
                    html(DONE_HTML)
                }
                else -> html(DONE_HTML)
            }
        } catch (e: Exception) {
            html(DONE_HTML)
        }
    }

    private fun html(body: String) =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)

    private fun parseForm(body: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        body.split('&').forEach { kv ->
            val k = URLDecoder.decode(kv.substringBefore('='), "UTF-8")
            val v = URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
            if (k.isNotEmpty()) out[k] = v
        }
        return out
    }

    companion object {
        private val FORM_HTML = """
<!DOCTYPE html>
<html lang="zh"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>填写电视播放器</title>
<style>
  body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#0e0e12;color:#f2f2f5;padding:24px;max-width:480px;margin:0 auto}
  h1{font-size:20px;margin:0 0 4px} p{color:#9aa0a8;font-size:13px;margin:0 0 20px}
  label{display:block;font-size:13px;color:#9aa0a8;margin:14px 0 4px}
  input{width:100%;box-sizing:border-box;padding:12px;font-size:16px;background:#1a1a20;color:#f2f2f5;border:1px solid #333;border-radius:8px}
  button{width:100%;margin-top:22px;padding:14px;font-size:16px;font-weight:600;color:#fff;background:#2196f3;border:0;border-radius:8px}
</style></head><body>
<h1>填写电视播放器</h1>
<p>填好后点「同步到电视」，内容会立即同步到电视上的输入框。</p>
<form method="post" action="/">
  <label>服务器清单页地址</label>
  <input name="bcom" placeholder="https://.../?password=..." autocomplete="off">
  <label>WebDAV 用户名</label>
  <input name="user" autocomplete="username">
  <label>WebDAV 密码</label>
  <input name="pass" type="password" autocomplete="current-password">
  <button type="submit">同步到电视</button>
</form>
</body></html>
        """.trimIndent()

        private val DONE_HTML = """
<!DOCTYPE html>
<html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>已同步</title>
<style>
 body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#0e0e12;color:#f2f2f5;text-align:center;padding-top:80px}
 h1{font-size:22px} p{color:#9aa0a8;font-size:14px}
</style></head><body>
<h1>✓ 已同步到电视</h1>
<p>可以回电视上继续操作了。</p>
</body></html>
        """.trimIndent()
    }
}
