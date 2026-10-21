package com.mojtaba.mojitranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.WindowManager.LayoutParams
import android.os.Looper
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

class OverlayService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "mojitranslate_capture"
        private const val NOTIFICATION_ID = 501
    }

    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private var resultView: TextView? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: Handler? = null
    private val busy = AtomicBoolean(false)
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val thread = HandlerThread("MojiTranslateOCR").apply { start() }
        worker = Handler(thread.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data != null && projection == null) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, Handler(Looper.getMainLooper()))
            showBubble()
        }
        return START_NOT_STICKY
    }

    private fun showBubble() {
        val view = TextView(this).apply {
            text = "مترجم"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16, 12, 16, 12)
            background = GradientDrawable().apply {
                setColor(Color.rgb(75, 55, 135))
                cornerRadius = 48f
            }
            elevation = 10f
            setOnClickListener { captureAndTranslate() }
        }
        bubble = view
        val params = LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 280
        }
        windowManager.addView(view, params)
    }

    private fun captureAndTranslate() {
        if (!busy.compareAndSet(false, true)) return
        bubble?.text = "..."
        try {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            if (imageReader == null) {
                imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
                imageReader?.setOnImageAvailableListener({ reader ->
                    val image = try { reader.acquireLatestImage() } catch (_: Exception) { null }
                    if (image != null) {
                        try {
                            val plane = image.planes[0]
                            val pixelStride = plane.pixelStride
                            val rowStride = plane.rowStride
                            val rowPadding = rowStride - pixelStride * metrics.widthPixels
                            val padded = Bitmap.createBitmap(
                                metrics.widthPixels + rowPadding / pixelStride,
                                metrics.heightPixels, Bitmap.Config.ARGB_8888
                            )
                            padded.copyPixelsFromBuffer(plane.buffer)
                            val bitmap = Bitmap.createBitmap(padded, 0, 0, metrics.widthPixels, metrics.heightPixels)
                            padded.recycle()
                            image.close()
                            worker?.post { runOcr(bitmap) }
                        } catch (_: Exception) {
                            image.close()
                            finishAttempt("گرفتن تصویر ناموفق بود")
                        }
                    }
                }, worker)
                virtualDisplay = projection?.createVirtualDisplay(
                    "MojiTranslateCapture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, worker
                )
            }
            // Never drain ImageReader here: its listener must receive the next frame.
        } catch (_: Exception) {
            finishAttempt("خطا در ضبط صفحه")
        }
    }

    private fun runOcr(bitmap: Bitmap) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                bitmap.recycle()
                val text = result.text.trim()
                if (text.isBlank()) {
                    finishAttempt("متن انگلیسی قابل‌خواندن پیدا نشد")
                } else {
                    // This app is intentionally optimized for English-to-Persian translation.
                    translateText(text)
                }
            }
            .addOnFailureListener {
                bitmap.recycle()
                finishAttempt("خواندن متن انگلیسی ناموفق بود")
            }
    }

    private fun translateText(text: String) {
        worker?.post {
            try {
                val chunks = splitForRequest(text, 900)
                val translated = StringBuilder()
                for (chunk in chunks) {
                    if (translated.isNotEmpty()) translated.append(' ')
                    translated.append(requestGoogleTranslate(chunk))
                }
                if (translated.isBlank()) {
                    finishAttempt("سرویس ترجمه پاسخ خالی داد")
                } else {
                    showResult(translated.toString().trim())
                }
            } catch (_: Exception) {
                finishAttempt("ترجمهٔ آنلاین ناموفق بود؛ اتصال اینترنت یا دسترسی به سرویس ترجمه را بررسی کن")
            }
        }
    }

    private fun splitForRequest(text: String, maxLength: Int): List<String> {
        val chunks = mutableListOf<String>()
        var remaining = text.trim()
        while (remaining.length > maxLength) {
            var splitAt = remaining.lastIndexOf(' ', maxLength)
            if (splitAt < maxLength / 2) splitAt = maxLength
            chunks.add(remaining.substring(0, splitAt).trim())
            remaining = remaining.substring(splitAt).trim()
        }
        if (remaining.isNotEmpty()) chunks.add(remaining)
        return chunks
    }

    private fun requestGoogleTranslate(text: String): String {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=fa&dt=t&q=$encoded")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 20000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) MojiTranslate")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("Translation HTTP $status")
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONArray(body)
            val segments = root.optJSONArray(0) ?: throw IllegalStateException("Invalid translation response")
            val output = StringBuilder()
            for (i in 0 until segments.length()) {
                val segment = segments.optJSONArray(i)
                val part = segment?.optString(0).orEmpty()
                if (part.isNotEmpty() && part != "null") output.append(part)
            }
            return output.toString().ifBlank { throw IllegalStateException("Empty translation") }
        } finally {
            connection.disconnect()
        }
    }

    private fun showResult(text: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                if (resultView == null) {
                    resultView = TextView(this).apply {
                        setTextColor(Color.WHITE)
                        textSize = 16f
                        setPadding(22, 18, 22, 18)
                        background = GradientDrawable().apply {
                            setColor(Color.rgb(35, 35, 40))
                            cornerRadius = 22f
                        }
                        setOnClickListener { windowManager.removeView(this); resultView = null }
                    }
                    val params = LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
                        LayoutParams.TYPE_APPLICATION_OVERLAY,
                        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.BOTTOM
                        x = 18
                        y = 70
                    }
                    windowManager.addView(resultView, params)
                }
                resultView?.text = "ترجمهٔ آنلاین انگلیسی به فارسی\n\n$text\n\nبرای بستن، لمس کن"
            } catch (_: Exception) { }
            finishAttempt(null)
        }
    }

    private fun finishAttempt(message: String?) {
        Handler(Looper.getMainLooper()).post {
            busy.set(false)
            bubble?.text = "مترجم"
            if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "MojiTranslate", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("MojiTranslate فعال است")
            .setContentText("برای ترجمهٔ آنلاین، دکمهٔ شناور را لمس کن")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        try { bubble?.let { windowManager.removeView(it) } } catch (_: Exception) { }
        try { resultView?.let { windowManager.removeView(it) } } catch (_: Exception) { }
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        recognizer.close()
        worker?.looper?.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
