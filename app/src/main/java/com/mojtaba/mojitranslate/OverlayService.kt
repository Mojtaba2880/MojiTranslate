package com.mojtaba.mojitranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.Translation
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
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
    private var regionOverlay: View? = null
    private var chosenRegion: Rect? = null
    private var captureOffsetX = 0
    private var captureOffsetY = 0
    private val translationViews = mutableListOf<TextView>()
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: Handler? = null
    private val translationExecutor = Executors.newFixedThreadPool(6)
    private val translationCache = ConcurrentHashMap<String, String>()
    private val translatorCache = ConcurrentHashMap<String, Translator>()
    private val busy = AtomicBoolean(false)
    private val captureArmed = AtomicBoolean(false)
    private val recognizers: List<TextRecognizer> by lazy {
        listOf(
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        )
    }

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
                            if (translationViews.isNotEmpty()) clearTranslations() else if (AppSettings.prefs(this@OverlayService).getBoolean("select_region_each_time", false)) selectRegion() else captureAndTranslate()
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

    private fun selectRegion() {
        var x1 = 0f; var y1 = 0f; var x2 = 0f; var y2 = 0f
        val fill = Paint().apply { color = Color.argb(80, 40, 150, 255); style = Paint.Style.FILL }
        val line = Paint().apply { color = Color.BLUE; style = Paint.Style.STROKE; strokeWidth = 4f }
        val v = object : View(this) {
            override fun onDraw(c: Canvas) {
                c.drawColor(Color.argb(35, 0, 0, 0))
                if (x1 != x2 && y1 != y2) { c.drawRect(min(x1,x2), min(y1,y2), max(x1,x2), max(y1,y2), fill); c.drawRect(min(x1,x2), min(y1,y2), max(x1,x2), max(y1,y2), line) }
            }
        }
        regionOverlay = v
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { x1=e.x; y1=e.y; x2=x1; y2=y1; v.invalidate(); true }
                MotionEvent.ACTION_MOVE -> { x2=e.x; y2=e.y; v.invalidate(); true }
                MotionEvent.ACTION_UP -> {
                    x2=e.x; y2=e.y
                    val l=min(x1,x2).toInt().coerceAtLeast(0); val t=min(y1,y2).toInt().coerceAtLeast(0)
                    val r=max(x1,x2).toInt(); val b=max(y1,y2).toInt()
                    try { windowManager.removeView(v) } catch (_: Exception) {}
                    regionOverlay=null
                    if (r-l >= 24 && b-t >= 24) { chosenRegion=Rect(l,t,r,b); captureAndTranslate() }
                    else { chosenRegion=null; Toast.makeText(this,"محدوده خیلی کوچک است",Toast.LENGTH_SHORT).show() }
                    true
                }
                else -> true
            }
        }
        val p = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.TOP or Gravity.LEFT
        try { windowManager.addView(v,p) } catch (_: Exception) { regionOverlay=null; Toast.makeText(this,"انتخاب محدوده ناموفق بود",Toast.LENGTH_SHORT).show() }
    }

    private fun captureAndTranslate() {
        if (!busy.compareAndSet(false, true)) return
        captureArmed.set(true)
        // Keep the floating control visible while capture starts; never make it look as if it vanished.
        bubble?.text = "در حال ترجمه…"
        clearTranslations()
        // If Android does not deliver an ImageReader frame, release the busy state and report it.
        worker?.postDelayed({
            if (captureArmed.compareAndSet(true, false)) {
                finishAttempt("تصویر صفحه دریافت نشد؛ مجوز ضبط صفحه را دوباره فعال کن و مجدداً تلاش کن")
            }
        }, 5000)
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
                            val region = chosenRegion
                            chosenRegion = null
                            captureOffsetX = region?.left ?: 0
                            captureOffsetY = region?.top ?: 0
                            val selected = if (region == null) bitmap else {
                                val l = region.left.coerceIn(0, bitmap.width - 1); val t = region.top.coerceIn(0, bitmap.height - 1)
                                val r = region.right.coerceIn(l + 1, bitmap.width); val b = region.bottom.coerceIn(t + 1, bitmap.height)
                                val crop = Bitmap.createBitmap(bitmap,l,t,r-l,b-t); bitmap.recycle(); crop
                            }
                            worker?.post { runOcr(selected) }
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
        val enhanced = try { makeEnhancedOcrBitmap(bitmap) } catch (_: Exception) { null }
        val thresholded = try { makeThresholdOcrBitmap(bitmap) } catch (_: Exception) { null }
        val collected = java.util.Collections.synchronizedList(mutableListOf<OcrLine>())
        val finished = java.util.concurrent.atomic.AtomicInteger(0)
        val passInputs = mutableListOf<Pair<Bitmap, Float>>()
        passInputs.add(bitmap to 1f)
        if (enhanced != null) passInputs.add(enhanced to (bitmap.width.toFloat() / enhanced.width))
        val tasks = mutableListOf<Pair<TextRecognizer, Pair<Bitmap, Float>>>()
        // Full enhancement passes are reserved for Latin OCR; other script recognizers use the original image.
        for ((index, recognizer) in recognizers.withIndex()) {
            if (index == 0) for (input in passInputs) tasks.add(recognizer to input)
            else tasks.add(recognizer to (bitmap to 1f))
        }
        if (thresholded != null) tasks.add(recognizers.first() to (thresholded to 1f))

        fun finishOnePass() {
            if (finished.incrementAndGet() != tasks.size) return
            val lines = deduplicateOcrLines(collected.toList())
            if (enhanced != null && !enhanced.isRecycled) enhanced.recycle()
            if (thresholded != null && !thresholded.isRecycled) thresholded.recycle()
            if (lines.isEmpty()) {
                if (!bitmap.isRecycled) bitmap.recycle()
                finishAttempt("متن قابل‌خواندن پیدا نشد؛ ممکن است متن خیلی ریز، پیچیده یا با خط پشتیبانی‌نشده باشد")
            } else {
                translateLines(lines, bitmap)
            }
        }

        for ((recognizer, input) in tasks) {
            val (imageBitmap, scale) = input
            try {
                recognizer.process(InputImage.fromBitmap(imageBitmap, 0))
                    .addOnSuccessListener { result ->
                        result.textBlocks.forEach { block ->
                            val rect = block.boundingBox ?: return@forEach
                            val text = block.text.trim()
                            if (text.isNotBlank() && text.any { it.isLetterOrDigit() }) {
                                val mapped = Rect(
                                    (rect.left * scale).toInt() + captureOffsetX, (rect.top * scale).toInt() + captureOffsetY,
                                    (rect.right * scale).toInt() + captureOffsetX, (rect.bottom * scale).toInt() + captureOffsetY
                                )
                                if (mapped.width() > 0 && mapped.height() > 0) collected.add(OcrLine(text, mapped))
                            }
                        }
                        finishOnePass()
                    }
                    .addOnFailureListener { finishOnePass() }
            } catch (_: Exception) {
                finishOnePass()
            }
        }
    }

    private fun makeThresholdOcrBitmap(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val color = pixels[i]
            val gray = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)).toInt()
            // Hard contrast helps OCR separate letter strokes from textured backgrounds.
            val value = if (gray >= 155) 255 else 0
            pixels[i] = Color.rgb(value, value, value)
        }
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    private fun isLatinLetter(ch: Char): Boolean {
        val code = ch.code
        return code in 0x0041..0x005A || code in 0x0061..0x007A ||
            code in 0x00C0..0x024F || code in 0x1E00..0x1EFF ||
            code in 0x2C60..0x2C7F || code in 0xA720..0xA7FF ||
            code in 0xAB30..0xAB6F
    }

    private fun makeEnhancedOcrBitmap(source: Bitmap): Bitmap {
        // Upscale small on-screen text before OCR, then apply grayscale contrast enhancement.
        // Keep the source bitmap unchanged for accurate overlay coordinates and background sampling.
        val scale = 1.5f
        val width = (source.width * scale).toInt().coerceAtLeast(source.width)
        val height = (source.height * scale).toInt().coerceAtLeast(source.height)
        val enlarged = Bitmap.createScaledBitmap(source, width, height, true)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        enlarged.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val color = pixels[i]
            val gray = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)).toInt()
            val adjusted = (((gray - 128) * 1.55f) + 128).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(adjusted, adjusted, adjusted)
        }
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        if (enlarged !== source && !enlarged.isRecycled) enlarged.recycle()
        return output
    }

    private fun deduplicateOcrLines(lines: List<OcrLine>): List<OcrLine> {
        val result = mutableListOf<OcrLine>()
        for (line in lines.sortedWith(compareBy<OcrLine> { it.bounds.top }.thenBy { it.bounds.left })) {
            val index = result.indexOfFirst { old ->
                val overlap = Rect(old.bounds)
                val intersects = overlap.intersect(line.bounds)
                val minArea = min(old.bounds.width() * old.bounds.height(), line.bounds.width() * line.bounds.height()).coerceAtLeast(1)
                intersects && overlap.width() * overlap.height() > minArea * 0.45 &&
                    old.text.lowercase().filter { it.isLetterOrDigit() } == line.text.lowercase().filter { it.isLetterOrDigit() }
            }
            if (index < 0) result.add(line)
            else if (line.text.length > result[index].text.length) result[index] = line
        }
        return result.sortedWith(compareBy<OcrLine> { it.bounds.top }.thenBy { it.bounds.left })
    }

    private fun translateLines(lines: List<OcrLine>, bitmap: Bitmap) {
        worker?.post {
            try {
                // Run independent line requests in parallel; one failed request no longer discards all results.
                val futures = lines.map { line ->
                    translationExecutor.submit<TranslatedLine?> {
                        try {
                            // Translate any OCR line containing Latin-script letters, regardless of language.
                            val translated = requestGoogleTranslate(line.text)
                            TranslatedLine(line.bounds, translated, sampleBackground(bitmap, Rect(line.bounds).apply { offset(-captureOffsetX, -captureOffsetY) }))
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
                val translated = futures.mapNotNull { it.get() }
                Handler(Looper.getMainLooper()).post {
                    try {
                        if (translated.isEmpty()) {
                            finishAttempt("ترجمه انجام نشد؛ حالت انتخاب‌شده، اتصال اینترنت یا مدل دانلودشده را بررسی کن")
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
                finishAttempt("ترجمه انجام نشد؛ حالت انتخاب‌شده، اتصال اینترنت یا مدل دانلودشده را بررسی کن")
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
                    val textScale = AppSettings.prefs(this@OverlayService).getFloat(AppSettings.TEXT_SCALE, 1f)
                    textSize = max(8f, (line.bounds.height() / density) * 0.72f * textScale)
                    val coverAlpha = AppSettings.prefs(this@OverlayService).getInt(AppSettings.COVER_OPACITY, 230).coerceIn(0, 255)
                    setBackgroundColor(Color.argb(coverAlpha, Color.red(line.backgroundColor), Color.green(line.backgroundColor), Color.blue(line.backgroundColor)))
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
        if (key.isBlank()) throw IllegalArgumentException("Empty OCR text")
        val mode = AppSettings.prefs(this).getString(AppSettings.MODE, AppSettings.MODE_AUTO) ?: AppSettings.MODE_AUTO
        val cacheKey = mode + ":" + key
        translationCache[cacheKey]?.let { return it }
        var lastError: Exception? = null
        if (mode != AppSettings.MODE_OFFLINE) {
            for (host in listOf("translate.googleapis.com", "translate.google.com")) {
                var connection: HttpURLConnection? = null
                try {
                    val encoded = URLEncoder.encode(key, "UTF-8")
                    val url = URL("https://" + host + "/translate_a/single?client=gtx&sl=auto&tl=fa&dt=t&q=" + encoded)
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"; connectTimeout = 2500; readTimeout = 3500
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Android) MojiTranslate")
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("Accept-Language", "fa,en;q=0.8"); useCaches = false
                    }
                    val status = connection.responseCode
                    if (status !in 200..299) throw IllegalStateException("Translation HTTP $status")
                    val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val segments = JSONArray(body).optJSONArray(0) ?: throw IllegalStateException("Invalid translation response")
                    val output = StringBuilder()
                    for (i in 0 until segments.length()) {
                        val part = segments.optJSONArray(i)?.optString(0).orEmpty()
                        if (part.isNotEmpty() && part != "null") output.append(part)
                    }
                    val result = output.toString().trim().ifBlank { throw IllegalStateException("Empty translation") }
                    if (key.filterNot { it.isWhitespace() }.lowercase() == result.filterNot { it.isWhitespace() }.lowercase() && key.any { it.isLetter() }) {
                        throw IllegalStateException("Translation endpoint returned unchanged source text")
                    }
                    translationCache[cacheKey] = result
                    return result
                } catch (e: Exception) { lastError = e } finally { connection?.disconnect() }
            }
            if (mode == AppSettings.MODE_ONLINE) throw lastError ?: IllegalStateException("Online translation failed")
        }
        // Do not trigger large model downloads from the capture path: use only pre-downloaded models.
        try {
            val identifier = LanguageIdentification.getClient()
            val detectedTag = try { Tasks.await(identifier.identifyLanguage(key), 3500, java.util.concurrent.TimeUnit.MILLISECONDS) } finally { identifier.close() }
            val sourceLanguage = TranslateLanguage.fromLanguageTag(detectedTag)
            if (detectedTag.isNullOrBlank() || detectedTag == "und" || sourceLanguage.isNullOrBlank() || sourceLanguage == TranslateLanguage.PERSIAN) {
                throw IllegalStateException("زبان متن تشخیص داده نشد یا مدل ترجمهٔ آن پشتیبانی نمی‌شود")
            }
            val translator = translatorCache.computeIfAbsent(sourceLanguage) {
                val options = TranslatorOptions.Builder().setSourceLanguage(sourceLanguage).setTargetLanguage(TranslateLanguage.PERSIAN).build()
                Translation.getClient(options)
            }
            val localResult = Tasks.await(translator.translate(key), 12, java.util.concurrent.TimeUnit.SECONDS).trim()
            if (localResult.isBlank() || localResult.filterNot { it.isWhitespace() }.lowercase() == key.filterNot { it.isWhitespace() }.lowercase()) {
                throw IllegalStateException("مدل آفلاین آماده نیست یا ترجمهٔ معتبر تولید نکرد")
            }
            translationCache[cacheKey] = localResult
            return localResult
        } catch (e: Exception) { lastError = e }
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
            .setContentText("حالت ترجمه از تنظیمات MojiTranslate پیروی می‌کند")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        try { bubble?.let { windowManager.removeView(it) } } catch (_: Exception) { }
        try { regionOverlay?.let { windowManager.removeView(it) } } catch (_: Exception) { }
        regionOverlay = null
        clearTranslations()
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        recognizers.forEach { try { it.close() } catch (_: Exception) { } }
        translatorCache.values.forEach { try { it.close() } catch (_: Exception) { } }
        translatorCache.clear()
        translationExecutor.shutdownNow()
        worker?.looper?.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
