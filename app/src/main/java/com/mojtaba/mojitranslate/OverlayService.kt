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
import android.graphics.Matrix
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
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
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
    private var diagnosticView: TextView? = null
    private var activeOcrStartedAtMs = 0L
    private var regionOverlay: View? = null
    private var chosenRegion: Rect? = null
    private var captureOffsetX = 0
    private var captureOffsetY = 0
    private val translationViews = mutableListOf<TextView>()
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureDensityDpi = 0
    @Volatile private var cachedDownloadedTranslationLanguages: Set<String>? = null
    private var worker: Handler? = null
    private val translationExecutor = Executors.newFixedThreadPool(6)
    private val translationCache = ConcurrentHashMap<String, String>()
    private val translatorCache = ConcurrentHashMap<String, Translator>()
    private val busy = AtomicBoolean(false)
    private val captureArmed = AtomicBoolean(false)
    private val recognizersLazy = lazy {
        listOf(
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        )
    }
    private val recognizers: List<TextRecognizer> by recognizersLazy

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
            val prefs = AppSettings.prefs(this@OverlayService)
            x = prefs.getInt("bubble_x", 24).coerceAtLeast(0)
            y = prefs.getInt("bubble_y", 280).coerceAtLeast(0)
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
                            val position = BubblePositionPolicy.clamp(
                                startX + dx, startY + dy,
                                metrics.widthPixels, metrics.heightPixels, v.width, v.height
                            )
                            params.x = position.x
                            params.y = position.y
                            try { windowManager.updateViewLayout(v, params) } catch (_: Exception) { }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            // Persist once when a drag ends instead of writing preferences on every frame.
                            AppSettings.prefs(this@OverlayService).edit()
                                .putInt("bubble_x", params.x)
                                .putInt("bubble_y", params.y)
                                .apply()
                        } else {
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
        if (!busy.compareAndSet(false, true)) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "ترجمهٔ قبلی هنوز در حال اجراست", Toast.LENGTH_SHORT).show()
            }
            return
        }
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
            if (CaptureSurfacePolicy.needsRecreate(
                    readerPresent = imageReader != null,
                    displayPresent = virtualDisplay != null,
                    currentWidth = imageReader?.width ?: 0,
                    currentHeight = imageReader?.height ?: 0,
                    currentDensityDpi = captureDensityDpi,
                    requestedWidth = metrics.widthPixels,
                    requestedHeight = metrics.heightPixels,
                    requestedDensityDpi = metrics.densityDpi
                )) {
                // Release the previous display before closing its surface. This is required
                // after screen rotation, resolution changes, or display-density changes.
                virtualDisplay?.release()
                virtualDisplay = null
                try { imageReader?.close() } catch (_: Exception) { }
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
                            try { image.close() } catch (_: Exception) { }
                            finishAttempt("گرفتن تصویر ناموفق بود")
                        }
                    }
                }, worker)
                virtualDisplay = projection?.createVirtualDisplay(
                    "MojiTranslateCapture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, worker
                )
                captureDensityDpi = metrics.densityDpi
            }
        } catch (_: Exception) {
            finishAttempt("خطا در ضبط صفحه")
        }
    }

    private data class OcrPassInput(
        val bitmap: Bitmap,
        val scale: Float = 1f,
        val rotationDegrees: Float? = null
    )

    /**
     * Fast-first strategy: run source/enhanced OCR first. Expensive threshold,
     * inversion and rotation variants are created only when that result is sparse.
     */
    private fun runOcr(bitmap: Bitmap) {
        activeOcrStartedAtMs = android.os.SystemClock.elapsedRealtime()
        val enhanced = try { makeEnhancedOcrBitmap(bitmap) } catch (_: Exception) { null }
        val quickInputs = mutableListOf(OcrPassInput(bitmap))
        if (enhanced != null) {
            quickInputs.add(OcrPassInput(enhanced, bitmap.width.toFloat() / enhanced.width))
        }
        val quickResults = java.util.Collections.synchronizedList(mutableListOf<OcrLine>())

        // The first pass uses only the Latin recognizer. Other scripts and expensive
        // transforms are delayed until the source/enhanced pass is demonstrably sparse.
        launchOcrTasks(bitmap, quickInputs, useAllRecognizers = false, quickResults) {
            val quickLines = mergeAdjacentOcrLines(deduplicateOcrLines(quickResults.toList()))
            val quickChars = quickLines.sumOf { line -> line.text.count { it.isLetterOrDigit() } }
            if (!OcrPassPlanner.shouldRunHeavyPasses(quickLines.size, quickChars)) {
                if (enhanced != null && !enhanced.isRecycled) enhanced.recycle()
                finishOcrWithLines(bitmap, quickLines)
            } else {
                // Give additional script recognizers a chance on the unmodified and
                // contrast-enhanced images before allocating threshold/rotation variants.
                val extendedResults = java.util.Collections.synchronizedList(mutableListOf<OcrLine>())
                launchOcrTasks(bitmap, quickInputs, useAllRecognizers = true, extendedResults) {
                    val initialResults = quickResults.toList() + extendedResults.toList()
                    val recovered = mergeAdjacentOcrLines(deduplicateOcrLines(initialResults))
                    val recoveredChars = recovered.sumOf { line -> line.text.count { it.isLetterOrDigit() } }
                    if (enhanced != null && !enhanced.isRecycled) enhanced.recycle()
                    if (!OcrPassPlanner.shouldRunHeavyPasses(recovered.size, recoveredChars)) {
                        finishOcrWithLines(bitmap, recovered)
                    } else {
                        runHeavyOcrPasses(bitmap, initialResults)
                    }
                }
            }
        }
    }

    private fun runHeavyOcrPasses(bitmap: Bitmap, initialResults: List<OcrLine>) {
        val combinedResults = java.util.Collections.synchronizedList(mutableListOf<OcrLine>())
        combinedResults.addAll(initialResults)
        val factories = buildHeavyOcrPassFactories(bitmap)
        lateinit var processNext: (Int) -> Unit
        processNext = { index ->
            val accumulated = mergeAdjacentOcrLines(deduplicateOcrLines(combinedResults.toList()))
            val chars = accumulated.sumOf { line -> line.text.count { it.isLetterOrDigit() } }
            if (index >= factories.size || !OcrPassPlanner.shouldRunHeavyPasses(accumulated.size, chars)) {
                // Stop early as soon as the heavy pass has recovered enough readable text.
                finishOcrWithLines(bitmap, combinedResults.toList())
            } else {
                val input = try { factories[index]() } catch (_: Exception) { null }
                if (input == null) {
                    processNext(index + 1)
                } else {
                    // Only one full-size variant exists and is being recognized at a time.
                    launchOcrTasks(bitmap, listOf(input), useAllRecognizers = false, combinedResults) {
                        recycleOcrInputs(listOf(input))
                        // Bitmap transforms run on the OCR worker, not the UI callback thread.
                        worker?.post { processNext(index + 1) } ?: processNext(index + 1)
                    }
                }
            }
        }
        worker?.post { processNext(0) } ?: processNext(0)
    }

    private fun buildHeavyOcrPassFactories(bitmap: Bitmap): List<() -> OcrPassInput?> = listOf(
        { OcrPassInput(makeThresholdOcrBitmap(bitmap, 155)) },
        { OcrPassInput(makeThresholdOcrBitmap(bitmap, 110)) },
        { OcrPassInput(makeThresholdOcrBitmap(bitmap, 200)) },
        { OcrPassInput(makeAdaptiveThresholdOcrBitmap(bitmap)) },
        { OcrPassInput(makeInvertedOcrBitmap(bitmap)) },
        {
            OcrPassInput(
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(180f) }, true),
                1f, 180f
            )
        },
        {
            OcrPassInput(
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(90f) }, true),
                1f, 90f
            )
        },
        {
            OcrPassInput(
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(270f) }, true),
                1f, 270f
            )
        },
        { OcrPassInput(makeRotatedOcrBitmap(bitmap, -5f), 1f, -5f) },
        { OcrPassInput(makeRotatedOcrBitmap(bitmap, 5f), 1f, 5f) },
        { OcrPassInput(makeRotatedOcrBitmap(bitmap, -10f), 1f, -10f) },
        { OcrPassInput(makeRotatedOcrBitmap(bitmap, 10f), 1f, 10f) },
        { OcrPassInput(makeRotatedOcrBitmap(bitmap, -15f), 1f, -15f) },
        { OcrPassInput(makeRotatedOcrBitmap(bitmap, 15f), 1f, 15f) },
        { OcrPassInput(makeRotatedOcrBitmap(bitmap, -30f), 1f, -30f) },
        { OcrPassInput(makeRotatedOcrBitmap(bitmap, 30f), 1f, 30f) }
    )

    private fun launchOcrTasks(
        sourceBitmap: Bitmap,
        inputs: List<OcrPassInput>,
        useAllRecognizers: Boolean,
        collected: MutableList<OcrLine>,
        onComplete: () -> Unit
    ) {
        val selectedRecognizers = if (useAllRecognizers) recognizers else listOf(recognizers.first())
        val tasks = inputs.flatMap { input -> selectedRecognizers.map { recognizer -> recognizer to input } }
        if (tasks.isEmpty()) {
            onComplete()
            return
        }
        val finished = java.util.concurrent.atomic.AtomicInteger(0)
        fun finishOneTask() {
            if (finished.incrementAndGet() == tasks.size) onComplete()
        }

        for ((recognizer, input) in tasks) {
            try {
                recognizer.process(InputImage.fromBitmap(input.bitmap, 0))
                    .addOnSuccessListener { result ->
                        result.textBlocks.forEach { block ->
                            val rect = block.boundingBox ?: return@forEach
                            val text = block.text.trim()
                            if (text.isNotBlank() && text.any { it.isLetterOrDigit() }) {
                                val mapped = mapOcrBounds(rect, sourceBitmap, input)
                                if (mapped.width() > 0 && mapped.height() > 0) {
                                    synchronized(collected) { collected.add(OcrLine(text, mapped)) }
                                }
                            }
                        }
                        finishOneTask()
                    }
                    .addOnFailureListener { finishOneTask() }
            } catch (_: Exception) {
                finishOneTask()
            }
        }
    }

    private fun mapOcrBounds(rect: Rect, sourceBitmap: Bitmap, input: OcrPassInput): Rect {
        val sourceRect = when (input.rotationDegrees) {
            180f -> Rect(
                sourceBitmap.width - rect.right, sourceBitmap.height - rect.bottom,
                sourceBitmap.width - rect.left, sourceBitmap.height - rect.top
            )
            90f -> Rect(
                rect.top, sourceBitmap.height - rect.right,
                rect.bottom, sourceBitmap.height - rect.left
            )
            270f -> Rect(
                sourceBitmap.width - rect.bottom, rect.left,
                sourceBitmap.width - rect.top, rect.right
            )
            -5f, 5f, -10f, 10f, -15f, 15f, -30f, 30f ->
                mapRotatedRect(rect, sourceBitmap.width, sourceBitmap.height, input.rotationDegrees)
            else -> rect
        }
        return Rect(
            (sourceRect.left * input.scale).toInt() + captureOffsetX,
            (sourceRect.top * input.scale).toInt() + captureOffsetY,
            (sourceRect.right * input.scale).toInt() + captureOffsetX,
            (sourceRect.bottom * input.scale).toInt() + captureOffsetY
        )
    }

    private fun recycleOcrInputs(inputs: List<OcrPassInput>) {
        inputs.map { it.bitmap }.distinct().forEach { image ->
            if (!image.isRecycled) image.recycle()
        }
    }

    private fun finishOcrWithLines(bitmap: Bitmap, rawLines: List<OcrLine>) {
        val lines = mergeAdjacentOcrLines(deduplicateOcrLines(rawLines))
        val recognizedChars = lines.sumOf { line -> line.text.count { it.isLetterOrDigit() } }
        // Sparse text still receives the overlapping-crop recovery pass.
        if (lines.isEmpty() || lines.size <= 2 || recognizedChars < 40) {
            retryOverlappingOcrCrops(bitmap, lines)
        } else {
            translateLines(lines, bitmap)
        }
    }

    private fun retryOverlappingOcrCrops(bitmap: Bitmap, existingLines: List<OcrLine>) {
        // Retry sparse OCR with overlapping crops in both dimensions. Bounded grid
        // regions give small captions more pixels per recognizer input while preserving
        // coverage near screen edges and avoiding an unbounded number of OCR tasks.
        val cropRanges = OcrCropPlanner.planRegions(bitmap.width, bitmap.height)
        if (cropRanges.isEmpty()) {
            if (existingLines.isNotEmpty()) translateLines(existingLines, bitmap)
            else {
                if (!bitmap.isRecycled) bitmap.recycle()
                finishAttempt("متن قابل‌خواندن پیدا نشد؛ ممکن است متن خیلی ریز، پیچیده یا با خط پشتیبانی‌نشده باشد")
            }
            return
        }
        val crops = mutableListOf<Triple<Bitmap, Int, Int>>()
        for (range in cropRanges) {
            try {
                val crop = Bitmap.createBitmap(bitmap, range.left, range.top, range.width, range.height)
                crops.add(Triple(crop, range.left, range.top))
            } catch (_: Exception) { }
        }
        // Curve rectification is only meaningful on a crop containing one detected text
        // line. Never estimate one displacement curve from a full-height strip: multiple
        // rows, icons, and background edges would produce a misleading warp.
        for (line in existingLines) {
            try {
                val paddingX = max(12, line.bounds.height() / 2)
                val paddingY = max(6, line.bounds.height() / 3)
                val left = (line.bounds.left - captureOffsetX - paddingX).coerceIn(0, bitmap.width - 1)
                val top = (line.bounds.top - captureOffsetY - paddingY).coerceIn(0, bitmap.height - 1)
                val right = (line.bounds.right - captureOffsetX + paddingX).coerceIn(left + 1, bitmap.width)
                val bottom = (line.bounds.bottom - captureOffsetY + paddingY).coerceIn(top + 1, bitmap.height)
                val lineCrop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                val rectified = try { makeCurvedRectifiedBitmap(lineCrop) } catch (_: Exception) { null }
                if (rectified != null) {
                    lineCrop.recycle()
                    crops.add(Triple(rectified, left, top))
                } else {
                    lineCrop.recycle()
                }
            } catch (_: Exception) { }
        }
        if (crops.isEmpty()) {
            if (existingLines.isNotEmpty()) {
                translateLines(existingLines, bitmap)
            } else {
                if (!bitmap.isRecycled) bitmap.recycle()
                finishAttempt("تلاش دوباره برای خواندن متن ناموفق بود")
            }
            return
        }
        val found = java.util.Collections.synchronizedList(mutableListOf<OcrLine>())
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val total = crops.size * recognizers.size
        for ((crop, leftOffset, topOffset) in crops) {
            for (recognizer in recognizers) {
                try {
                    recognizer.process(InputImage.fromBitmap(crop, 0))
                        .addOnSuccessListener { result ->
                            result.textBlocks.forEach { textBlock ->
                                val rect = textBlock.boundingBox ?: return@forEach
                                val text = textBlock.text.trim()
                                if (text.isNotBlank() && text.any { it.isLetterOrDigit() }) {
                                    found.add(OcrLine(text, Rect(
                                        rect.left + leftOffset + captureOffsetX,
                                        rect.top + topOffset + captureOffsetY,
                                        rect.right + leftOffset + captureOffsetX,
                                        rect.bottom + topOffset + captureOffsetY
                                    )))
                                }
                            }
                            finishCropRecognizerIfReady(completed, total, crops, found, bitmap, existingLines)
                        }
                        .addOnFailureListener {
                            finishCropRecognizerIfReady(completed, total, crops, found, bitmap, existingLines)
                        }
                } catch (_: Exception) {
                    finishCropRecognizerIfReady(completed, total, crops, found, bitmap, existingLines)
                }
            }
        }
    }

    private fun finishCropRecognizerIfReady(
        completed: java.util.concurrent.atomic.AtomicInteger,
        total: Int,
        crops: List<Triple<Bitmap, Int, Int>>,
        found: List<OcrLine>,
        bitmap: Bitmap,
        existingLines: List<OcrLine>
    ) {
        if (completed.incrementAndGet() != total) return
        crops.forEach { (crop, _, _) -> if (!crop.isRecycled) crop.recycle() }
        val combined = existingLines + found
        val lines = mergeAdjacentOcrLines(deduplicateOcrLines(combined))
        if (lines.isEmpty()) {
            if (!bitmap.isRecycled) bitmap.recycle()
            finishAttempt("متن قابل‌خواندن پیدا نشد؛ تلاش با برش‌های هم‌پوشان و چند زبان هم متنی پیدا نکرد")
        } else {
            translateLines(lines, bitmap)
        }
    }

    private fun finishCropRetryIfReady(
        completed: java.util.concurrent.atomic.AtomicInteger,
        total: Int,
        found: List<OcrLine>,
        bitmap: Bitmap,
        existingLines: List<OcrLine>
    ) {
        if (completed.incrementAndGet() != total) return
        val combined = existingLines + found
        val lines = mergeAdjacentOcrLines(deduplicateOcrLines(combined))
        if (lines.isEmpty()) {
            if (!bitmap.isRecycled) bitmap.recycle()
            finishAttempt("متن قابل‌خواندن پیدا نشد؛ حتی با برش‌های هم‌پوشان هم OCR متنی پیدا نکرد")
        } else {
            translateLines(lines, bitmap)
        }
    }

    private fun makeRotatedOcrBitmap(source: Bitmap, degrees: Float): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val matrix = Matrix().apply { setRotate(degrees, source.width / 2f, source.height / 2f) }
        canvas.drawBitmap(source, matrix, paint)
        return output
    }

    private fun mapRotatedRect(rect: Rect, width: Int, height: Int, degrees: Float): Rect {
        val mapped = OcrCoordinateMapper.mapRotatedBounds(
            rect.left, rect.top, rect.right, rect.bottom, width, height, degrees.toDouble()
        )
        return Rect(mapped.left, mapped.top, mapped.right, mapped.bottom)
    }

    private fun makeAdaptiveThresholdOcrBitmap(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixelCount = width.toLong() * height
        require(width > 0 && height > 0 && pixelCount <= Int.MAX_VALUE) {
            "Unsupported bitmap size for adaptive threshold"
        }

        // Keep only one grayscale byte and one binary byte per pixel. The previous
        // integral-image approach allocated an additional Long for every pixel, which
        // could consume over 100 MB for a single high-resolution game screenshot.
        val gray = ByteArray(pixelCount.toInt())
        val sourceRow = IntArray(width)
        for (y in 0 until height) {
            source.getPixels(sourceRow, 0, width, 0, y, width, 1)
            val rowOffset = y * width
            for (x in 0 until width) {
                val color = sourceRow[x]
                gray[rowOffset + x] = (
                    0.299 * Color.red(color) +
                        0.587 * Color.green(color) +
                        0.114 * Color.blue(color)
                    ).toInt().coerceIn(0, 255).toByte()
            }
        }

        val binary = OcrAdaptiveThreshold.binarize(gray, width, height)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val outputRow = IntArray(width)
        try {
            for (y in 0 until height) {
                val rowOffset = y * width
                for (x in 0 until width) {
                    val value = binary[rowOffset + x].toInt() and 0xFF
                    outputRow[x] = Color.rgb(value, value, value)
                }
                output.setPixels(outputRow, 0, width, 0, y, width, 1)
            }
            return output
        } catch (error: Exception) {
            output.recycle()
            throw error
        }
    }

    private fun makeThresholdOcrBitmap(source: Bitmap, threshold: Int): Bitmap {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val color = pixels[i]
            val gray = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)).toInt()
            // Hard contrast helps OCR separate letter strokes from textured backgrounds.
            val value = if (gray >= threshold) 255 else 0
            pixels[i] = Color.rgb(value, value, value)
        }
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    private fun makeInvertedOcrBitmap(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val color = pixels[i]
            val gray = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)).toInt()
            val inverted = 255 - gray
            pixels[i] = Color.rgb(inverted, inverted, inverted)
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

    private fun makeCurvedRectifiedBitmap(bitmap: Bitmap): Bitmap? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 80 || height < 20 || width.toLong() * height > 2_500_000L) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = IntArray(pixels.size)
        for (i in pixels.indices) {
            val color = pixels[i]
            gray[i] = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)).toInt()
        }
        val offsets = CurvedTextRectifier.estimateVerticalOffsets(width, height, gray) ?: return null
        val outPixels = IntArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) {
            val sourceY = (y + offsets[x]).toInt().coerceIn(0, height - 1)
            outPixels[y * width + x] = pixels[sourceY * width + x]
        }
        return Bitmap.createBitmap(outPixels, width, height, Bitmap.Config.ARGB_8888)
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
        val grayValues = IntArray(pixels.size)
        for (i in pixels.indices) {
            val color = pixels[i]
            grayValues[i] = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)).toInt().coerceIn(0, 255)
        }
        val adjustedValues = OcrContrast.stretch(grayValues)
        for (i in pixels.indices) {
            val adjusted = adjustedValues[i]
            pixels[i] = Color.rgb(adjusted, adjusted, adjusted)
        }
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        if (enlarged !== source && !enlarged.isRecycled) enlarged.recycle()
        return output
    }

    private fun mergeAdjacentOcrLines(lines: List<OcrLine>): List<OcrLine> =
        OcrLineLayout.mergeAdjacent(lines.map { line ->
            OcrLineCandidate(line.text, line.bounds.left, line.bounds.top, line.bounds.right, line.bounds.bottom)
        }).map { line ->
            OcrLine(line.text, Rect(line.left, line.top, line.right, line.bottom))
        }

    private fun deduplicateOcrLines(lines: List<OcrLine>): List<OcrLine> =
        OcrLineLayout.deduplicate(lines.map { line ->
            OcrLineCandidate(line.text, line.bounds.left, line.bounds.top, line.bounds.right, line.bounds.bottom)
        }).map { line ->
            OcrLine(line.text, Rect(line.left, line.top, line.right, line.bottom))
        }

    private fun shouldPreserveIdentifier(text: String): Boolean =
        TranslationTextPolicy.shouldPreserveIdentifier(text)

    private fun translateLines(lines: List<OcrLine>, bitmap: Bitmap) {
        val ocrElapsedMs = (android.os.SystemClock.elapsedRealtime() - activeOcrStartedAtMs).coerceAtLeast(0L)
        val sourcePreview = lines.take(5).joinToString("\n") { it.text }.take(260)
        showDiagnosticOverlay(
            "متن شناسایی‌شده (OCR، " + ocrElapsedMs + "ms):\n" + sourcePreview + "\nوضعیت: ترجمه در حال اجراست…",
            12000L
        )
        val translationStartedAtMs = android.os.SystemClock.elapsedRealtime()
        worker?.post {
            try {
                val failureCount = java.util.concurrent.atomic.AtomicInteger(0)
                val firstFailure = java.util.concurrent.atomic.AtomicReference<Exception?>(null)
                val futures = lines.map { line ->
                    translationExecutor.submit<TranslatedLine?> {
                        try {
                            val translated = if (shouldPreserveIdentifier(line.text)) line.text
                                else requestGoogleTranslateWithFallback(line.text)
                            TranslatedLine(line.bounds, translated, sampleBackground(bitmap, Rect(line.bounds).apply { offset(-captureOffsetX, -captureOffsetY) }))
                        } catch (error: Exception) {
                            failureCount.incrementAndGet()
                            firstFailure.compareAndSet(null, error)
                            null
                        }
                    }
                }
                val translated = futures.mapNotNull { it.get() }
                val translationElapsedMs = (android.os.SystemClock.elapsedRealtime() - translationStartedAtMs).coerceAtLeast(0L)
                Handler(Looper.getMainLooper()).post {
                    try {
                        if (translated.isEmpty()) {
                            val detail = firstFailure.get()?.message.orEmpty()
                            val userMessage = TranslationFailureClassifier.userMessage(detail)
                            showDiagnosticOverlay(
                                "OCR: موفق (" + ocrElapsedMs + "ms)\nترجمه: ناموفق (" + translationElapsedMs + "ms)\n" + userMessage,
                                10000L
                            )
                            finishAttempt(userMessage)
                        } else {
                            showInlineTranslations(translated)
                            if (translated.size < lines.size) {
                                val warning = "ترجمهٔ " + failureCount.get() + " خط از " + lines.size + " خط ناموفق بود؛ دوباره تلاش کن"
                                Toast.makeText(this, warning, Toast.LENGTH_LONG).show()
                                showDiagnosticOverlay(
                                    "OCR: موفق (" + ocrElapsedMs + "ms)\nترجمه: ناقص (" + translationElapsedMs + "ms، " + translated.size + "/" + lines.size + " خط)\n" + sourcePreview,
                                    9000L
                                )
                            } else {
                                showDiagnosticOverlay(
                                    "OCR: موفق (" + ocrElapsedMs + "ms)\nترجمه: موفق (" + translationElapsedMs + "ms)\n" + sourcePreview,
                                    7000L
                                )
                            }
                        }
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            } catch (error: Exception) {
                if (!bitmap.isRecycled) bitmap.recycle()
                val message = TranslationFailureClassifier.userMessage(error.message.orEmpty())
                showDiagnosticOverlay("OCR: موفق (" + ocrElapsedMs + "ms)\nترجمه: ناموفق\n" + message, 10000L)
                finishAttempt(message)
            }
        }
    }

    private fun showDiagnosticOverlay(message: String, dismissAfterMs: Long = 8000L) {
        if (!AppSettings.prefs(this).getBoolean(AppSettings.DIAGNOSTICS_ENABLED, true)) return
        Handler(Looper.getMainLooper()).post {
            clearDiagnosticOverlay()
            val view = TextView(this).apply {
                text = message
                textSize = 12f
                setTextColor(Color.WHITE)
                setPadding(12, 8, 12, 8)
                maxLines = 8
                background = GradientDrawable().apply {
                    setColor(Color.argb(218, 28, 28, 34))
                    cornerRadius = 12f
                }
            }
            val density = resources.displayMetrics.density
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                y = (44 * density).toInt()
            }
            try {
                windowManager.addView(view, params)
                diagnosticView = view
                Handler(Looper.getMainLooper()).postDelayed({
                    if (diagnosticView === view) clearDiagnosticOverlay()
                }, dismissAfterMs)
            } catch (_: Exception) {
                diagnosticView = null
            }
        }
    }

    private fun clearDiagnosticOverlay() {
        val view = diagnosticView ?: return
        try { windowManager.removeView(view) } catch (_: Exception) { }
        diagnosticView = null
    }

    private fun requestGoogleTranslateWithFallback(text: String): String {
        val mode = AppSettings.prefs(this).getString(AppSettings.MODE, AppSettings.MODE_AUTO)
            ?: AppSettings.MODE_AUTO
        // Avoid placeholder tokens during offline language identification/model inference;
        // online translation is protected so item IDs, numbers and compact codes survive.
        if (mode == AppSettings.MODE_OFFLINE) return requestGoogleTranslateWithFallbackRaw(text)
        val protected = TranslationTokenProtector.protect(text)
        return protected.restore(requestGoogleTranslateWithFallbackRaw(protected.text))
    }

    private fun requestGoogleTranslateWithFallbackRaw(text: String): String {
        try {
            return requestGoogleTranslate(text)
        } catch (originalError: Exception) {
            val mode = AppSettings.prefs(this).getString(AppSettings.MODE, AppSettings.MODE_AUTO)
                ?: AppSettings.MODE_AUTO
            // Offline models need the complete context and should not trigger network-style splitting.
            if (mode == AppSettings.MODE_OFFLINE) throw originalError

            val chunks = TranslationTextSplitter.split(text, maxWords = 18, maxChars = 420)
            if (chunks.size > 1) {
                try {
                    return TranslationTextPolicy.joinTranslatedChunks(chunks.map { requestGoogleTranslate(it) })
                } catch (_: Exception) {
                    // Continue with smaller, balanced word groups if a sentence chunk fails.
                }
            }

            val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size >= 4) {
                val midpoint = words.size / 2
                val smallerChunks = listOf(
                    words.take(midpoint).joinToString(" "),
                    words.drop(midpoint).joinToString(" ")
                )
                return TranslationTextPolicy.joinTranslatedChunks(smallerChunks.map { requestGoogleTranslate(it) })
            }
            throw originalError
        }
    }

    private fun sampleBackground(bitmap: Bitmap, bounds: Rect): Int {
        // Estimate the cover from many pixels just outside the OCR box. Per-channel
        // medians are less affected by a nearby icon, glow, or isolated contrasting pixel.
        val left = bounds.left.coerceIn(0, bitmap.width - 1)
        val right = bounds.right.coerceIn(left + 1, bitmap.width)
        val top = bounds.top.coerceIn(0, bitmap.height - 1)
        val bottom = bounds.bottom.coerceIn(top + 1, bitmap.height)
        val points = linkedSetOf<Pair<Int, Int>>()
        fun addIfInside(x: Int, y: Int) {
            if (x in 0 until bitmap.width && y in 0 until bitmap.height) points.add(x to y)
        }
        val xStep = ((right - left) / 8).coerceAtLeast(1)
        val yStep = ((bottom - top) / 6).coerceAtLeast(1)
        for (x in left..right step xStep) {
            addIfInside(x, top - 2)
            addIfInside(x, bottom)
        }
        for (y in top..bottom step yStep) {
            addIfInside(left - 2, y)
            addIfInside(right, y)
        }
        if (points.isEmpty()) return OcrBackgroundEstimator.estimate(intArrayOf())
        val colors = IntArray(points.size)
        var index = 0
        for ((x, y) in points) colors[index++] = bitmap.getPixel(x, y)
        return OcrBackgroundEstimator.estimate(colors)
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
                    // The label layer is touch-through so underlying game controls remain usable.
                    // Tap the floating bubble to dismiss translations without closing the bubble.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setAutoSizeTextTypeUniformWithConfiguration(7, 32, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                    }
                }
                val params = WindowManager.LayoutParams(
                    max(1, rect.width()), max(1, rect.height()),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
            val downloadedTags = getDownloadedTranslationLanguages()
            val missingTags = OfflineModelReadiness.missingLanguages(downloadedTags, sourceLanguage, TranslateLanguage.PERSIAN)
            if (missingTags.isNotEmpty()) {
                throw IllegalStateException(
                    "مدل آفلاین آماده نیست. مدل‌های زبان " + missingTags.joinToString(", ") +
                        " دانلود نشده‌اند؛ در صفحهٔ اصلی MojiTranslate زبان مبدأ را انتخاب کن و «دانلود مدل انتخاب‌شده» را بزن."
                )
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

    @Synchronized
    private fun getDownloadedTranslationLanguages(): Set<String> {
        cachedDownloadedTranslationLanguages?.let { return it }
        val models = Tasks.await(
            RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java),
            8, java.util.concurrent.TimeUnit.SECONDS
        )
        return models.map { it.language.lowercase() }.toSet().also { cachedDownloadedTranslationLanguages = it }
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
        clearDiagnosticOverlay()
        captureArmed.set(false)
        virtualDisplay?.release()
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) { }
        imageReader = null
        projection?.stop()
        projection = null
        captureDensityDpi = 0
        // Do not instantiate all five ML Kit recognizers just to close them when
        // the overlay is stopped before its first OCR attempt.
        if (recognizersLazy.isInitialized()) {
            recognizers.forEach { try { it.close() } catch (_: Exception) { } }
        }
        translatorCache.values.forEach { try { it.close() } catch (_: Exception) { } }
        translatorCache.clear()
        translationExecutor.shutdownNow()
        worker?.looper?.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
