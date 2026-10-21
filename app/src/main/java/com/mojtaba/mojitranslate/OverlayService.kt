package com.mojtaba.mojitranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "mojitranslate_capture"
        private const val NOTIFICATION_ID = 501
    }

    private data class OcrLine(val text: String, val bounds: Rect)
    private data class TranslatedLine(val bounds: Rect, val text: String, val backgroundColor: Int)

    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val translationViews = mutableListOf<TextView>()
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: Handler? = null
    private val translationExecutor = Executors.newFixedThreadPool(6)
    private val translationCache = ConcurrentHashMap<String, String>()
    private val busy = AtomicBoolean(false)
    private val captureArmed = AtomicBoolean(false)
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
        }
        bubble = view
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 24
            y = 280
        }
        bubbleParams = params
        view.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = params.x
                        startY = params.y
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()
                        if (abs(dx) > 6 || abs(dy) > 6) moved = true
                        if (moved) {
                            val metrics = resources.displayMetrics
                            params.x = (startX + dx).coerceIn(0, max(0, metrics.widthPixels - v.width))
                            params.y = (startY + dy).coerceIn(0, max(0, metrics.heightPixels - v.height))
                            try { windowManager.updateViewLayout(v, params) } catch (_: Exception) { }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            if (translationViews.isNotEmpty()) clearTranslations() else captureAndTranslate()
                        }
                        v.performClick()
                        return true
                    }
                }
                return true
            }
        })
        windowManager.addView(view, params)
    }

    private fun captureAndTranslate() {
        if (!busy.compareAndSet(false, true)) return
        captureArmed.set(true)
        bubble?.text = "..."
        clearTranslations()
        try {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            if (imageReader == null) {
                imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
                imageReader?.setOnImageAvailableListener({ reader ->
                    val image = try { reader.acquireLatestImage() } catch (_: Exception) { null }
                    if (!captureArmed.compareAndSet(true, false)) {
                        try { image?.close() } catch (_: Exception) { }
                        return@setOnImageAvailableListener
                    }
                    if (image == null) {
                        finishAttempt("گرفتن تصویر صفحه ناموفق بود؛ دوباره تلاش کن")
                        return@setOnImageAvailableListener
                    }
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
        } catch (_: Exception) {
            finishAttempt("خطا در ضبط صفحه")
        }
    }

    private fun runOcr(bitmap: Bitmap) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val lines = result.textBlocks.flatMap { block ->
                    block.lines.mapNotNull { line ->
                        val rect = line.boundingBox
                        val text = line.text.trim()
                        if (rect != null && text.isNotBlank()) OcrLine(text, Rect(rect)) else null
                    }
                }.filter {
                    it.bounds.width() > 0 && it.bounds.height() > 0 &&
                        it.text.any { ch -> ch in 'A'..'Z' || ch in 'a'..'z' || ch in '\u00C0'..'\u024F' }
                }
                if (lines.isEmpty()) {
                    bitmap.recycle()
                    finishAttempt("متن انگلیسی قابل‌خواندن پیدا نشد")
                } else {
                    translateLines(lines, bitmap)
                }
            }
            .addOnFailureListener {
                bitmap.recycle()
                finishAttempt("خواندن متن ناموفق بود")
            }
    }

    private fun translateLines(lines: List<OcrLine>, bitmap: Bitmap) {
        worker?.post {
            try {
                // Run independent line requests in parallel; one failed request no longer discards all results.
                val futures = lines.map { line ->
                    translationExecutor.submit<TranslatedLine?> {
                        try {
                            val translated = requestGoogleTranslate(line.text)
                            TranslatedLine(line.bounds, translated, sampleBackground(bitmap, line.bounds))
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
                val translated = futures.mapNotNull { it.get() }
                Handler(Looper.getMainLooper()).post {
                    try {
                        if (translated.isEmpty()) {
                            finishAttempt("ترجمهٔ آنلاین ناموفق بود؛ اتصال اینترنت را بررسی کن")
                        } else {
                            showInlineTranslations(translated)
                            if (translated.size < lines.size) {
                                Toast.makeText(this, "بخشی از متن‌ها ترجمه نشدند؛ دوباره تلاش کن", Toast.LENGTH_LONG).show()
                            }
                        }
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            } catch (_: Exception) {
                if (!bitmap.isRecycled) bitmap.recycle()
                finishAttempt("ترجمهٔ آنلاین ناموفق بود؛ اینترنت یا دسترسی به سرویس ترجمه را بررسی کن")
            }
        }
    }

    private fun sampleBackground(bitmap: Bitmap, bounds: Rect): Int {
        // Approximate the line's background using pixels immediately outside its OCR box.
        // This is a best-effort cover; it cannot reconstruct complex images or textures.
        val left = bounds.left.coerceIn(0, bitmap.width - 1)
        val right = bounds.right.coerceIn(left + 1, bitmap.width)
        val top = bounds.top.coerceIn(0, bitmap.height - 1)
        val bottom = bounds.bottom.coerceIn(top + 1, bitmap.height)
        val points = listOf(
            left to max(0, top - 2),
            min(bitmap.width - 1, right) to max(0, top - 2),
            left to min(bitmap.height - 1, bottom),
            min(bitmap.width - 1, right) to min(bitmap.height - 1, bottom),
            max(0, left - 2) to ((top + bottom) / 2).coerceIn(0, bitmap.height - 1),
            min(bitmap.width - 1, right) to ((top + bottom) / 2).coerceIn(0, bitmap.height - 1)
        )
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        for ((x, y) in points) {
            val color = bitmap.getPixel(x, y)
            red += Color.red(color)
            green += Color.green(color)
            blue += Color.blue(color)
            count++
        }
        return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun showInlineTranslations(lines: List<TranslatedLine>) {
        try {
            clearTranslations()
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val density = resources.displayMetrics.density
            for (line in lines) {
                val rect = Rect(line.bounds)
                val pad = max(2, (2 * density).toInt())
                rect.inset(-pad, -pad)
                rect.left = rect.left.coerceIn(0, metrics.widthPixels - 1)
                rect.right = rect.right.coerceIn(rect.left + 1, metrics.widthPixels)
                rect.top = rect.top.coerceIn(0, metrics.heightPixels - 1)
                rect.bottom = rect.bottom.coerceIn(rect.top + 1, metrics.heightPixels)

                val view = TextView(this).apply {
                    text = line.text
                    setTextColor(contrastingTextColor(line.backgroundColor))
                    textDirection = View.TEXT_DIRECTION_RTL
                    gravity = Gravity.CENTER
                    setPadding(2, 0, 2, 0)
                    textSize = max(8f, (line.bounds.height() / density) * 0.72f)
                    setBackgroundColor(line.backgroundColor)
                    // Touching any translated line dismisses the translated layer.
                    setOnClickListener { clearTranslations() }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setAutoSizeTextTypeUniformWithConfiguration(7, 32, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                    }
                }
                val params = WindowManager.LayoutParams(
                    max(1, rect.width()), max(1, rect.height()),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.LEFT
                    x = rect.left
                    y = rect.top
                }
                windowManager.addView(view, params)
                translationViews.add(view)
            }
        } catch (_: Exception) {
            clearTranslations()
            Toast.makeText(this, "نمایش ترجمه روی صفحه ناموفق بود", Toast.LENGTH_LONG).show()
        } finally {
            finishAttempt(null)
        }
    }

    private fun contrastingTextColor(background: Int): Int {
        val luminance = (0.299 * Color.red(background) + 0.587 * Color.green(background) + 0.114 * Color.blue(background))
        return if (luminance > 150) Color.BLACK else Color.WHITE
    }

    private fun clearTranslations() {
        for (view in translationViews.toList()) {
            try { windowManager.removeView(view) } catch (_: Exception) { }
        }
        translationViews.clear()
    }

    private fun requestGoogleTranslate(text: String): String {
        val key = text.trim()
        translationCache[key]?.let { return it }
        var lastError: Exception? = null
        repeat(2) { attempt ->
            var connection: HttpURLConnection? = null
            try {
                val encoded = URLEncoder.encode(key, "UTF-8")
                val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=fa&dt=t&q=$encoded")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4500
                    readTimeout = 6500
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android) MojiTranslate")
                    setRequestProperty("Accept", "application/json")
                    useCaches = true
                }
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
                val result = output.toString().ifBlank { throw IllegalStateException("Empty translation") }
                translationCache[key] = result
                return result
            } catch (e: Exception) {
                lastError = e
                if (attempt == 0) Thread.sleep(180)
            } finally {
                connection?.disconnect()
            }
        }
        throw lastError ?: IllegalStateException("Translation failed")
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
        clearTranslations()
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        recognizer.close()
        translationExecutor.shutdownNow()
        worker?.looper?.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
