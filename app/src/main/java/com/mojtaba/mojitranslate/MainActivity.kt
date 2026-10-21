package com.mojtaba.mojitranslate

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val captureRequest = 4107
    private val modelExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var downloadStatus: TextView? = null
    private var downloadButton: Button? = null
    private var languageSpinner: Spinner? = null
    private var activeDownload: Translator? = null
    private val languages = listOf(
        "Afrikaans" to "af", "Arabic" to "ar", "Belarusian" to "be",
        "Bulgarian" to "bg", "Bengali" to "bn", "Catalan" to "ca",
        "Czech" to "cs", "Welsh" to "cy", "Danish" to "da",
        "German" to "de", "Greek" to "el", "English" to "en",
        "Esperanto" to "eo", "Spanish" to "es", "Estonian" to "et",
        "Persian" to "fa", "Finnish" to "fi", "French" to "fr",
        "Irish" to "ga", "Galician" to "gl", "Gujarati" to "gu",
        "Hebrew" to "he", "Hindi" to "hi", "Croatian" to "hr",
        "Haitian Creole" to "ht", "Hungarian" to "hu", "Indonesian" to "id",
        "Icelandic" to "is", "Italian" to "it", "Japanese" to "ja",
        "Georgian" to "ka", "Kannada" to "kn", "Korean" to "ko",
        "Lithuanian" to "lt", "Latvian" to "lv", "Macedonian" to "mk",
        "Marathi" to "mr", "Malay" to "ms", "Maltese" to "mt",
        "Dutch" to "nl", "Norwegian" to "no", "Polish" to "pl",
        "Portuguese" to "pt", "Romanian" to "ro", "Russian" to "ru",
        "Slovak" to "sk", "Slovenian" to "sl", "Albanian" to "sq",
        "Swedish" to "sv", "Swahili" to "sw", "Tamil" to "ta",
        "Telugu" to "te", "Thai" to "th", "Tagalog" to "tl",
        "Turkish" to "tr", "Ukrainian" to "uk", "Urdu" to "ur",
        "Vietnamese" to "vi", "Chinese" to "zh"
    ).filter { TranslateLanguage.fromLanguageTag(it.second) != null }
        .sortedBy { it.first }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 28)
        }
        root.addView(TextView(this).apply {
            text = "MojiTranslate"
            textSize = 26f
        })
        root.addView(TextView(this).apply {
            text = "ترجمهٔ متن قابل‌شناسایی روی صفحه به فارسی. ابتدا ترجمهٔ آنلاین رایگان امتحان می‌شود؛ در صورت شکست، مدل آفلاینِ زبان مربوطه استفاده می‌شود. برای جلوگیری از انتظار هنگام قطع اینترنت، مدل زبان را از قبل در بخش زیر دانلود کن."
            textSize = 16f
            setPadding(0, 20, 0, 20)
        })

        root.addView(TextView(this).apply {
            text = "دانلود زبان‌ها برای ترجمهٔ آفلاین"
            textSize = 19f
            setPadding(0, 12, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "زبان متن اصلی را انتخاب کن. مدل آن زبان به‌همراه مدل مقصد فارسی دانلود می‌شود؛ اتصال اینترنت لازم است. هر مدل ممکن است چند ده مگابایت حجم داشته باشد."
            textSize = 14f
            setPadding(0, 0, 0, 8)
        })
        languageSpinner = Spinner(this).also { spinner ->
            spinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                languages.map { "${it.first} (${it.second})" }
            )
            root.addView(spinner, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        downloadButton = Button(this).apply {
            text = "دانلود مدل انتخاب‌شده"
            setOnClickListener { downloadSelectedModel() }
            root.addView(this)
        }
        downloadStatus = TextView(this).apply {
            text = "هنوز دانلودی از این بخش شروع نشده است."
            textSize = 14f
            setPadding(0, 4, 0, 16)
            root.addView(this)
        }

        root.addView(Button(this).apply {
            text = "۱. اجازهٔ نمایش روی برنامه‌های دیگر"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                } else Toast.makeText(this@MainActivity, "این مجوز قبلاً فعال است.", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = "۲. فعال‌کردن دکمهٔ شناور ترجمه"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "ابتدا مجوز نمایش روی برنامه‌های دیگر را فعال کن.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
            }
        })
        root.addView(TextView(this).apply {
            text = "محدودیت: ترجمهٔ آفلاین فقط برای زبان‌هایی کار می‌کند که مدلشان با موفقیت دانلود شده باشد. تشخیص متن نیز ممکن است در فونت‌های تزئینی، متن ریز، افکت‌های شدید یا زبان‌های پشتیبانی‌نشده خطا کند."
            textSize = 13f
            setPadding(0, 20, 0, 0)
        })
        setContentView(root)
    }

    private fun downloadSelectedModel() {
        val index = languageSpinner?.selectedItemPosition ?: return
        val entry = languages.getOrNull(index) ?: return
        val source = TranslateLanguage.fromLanguageTag(entry.second)
        val target = TranslateLanguage.PERSIAN
        if (source.isNullOrBlank() || source == target) {
            Toast.makeText(this, "زبان انتخاب‌شده برای ترجمهٔ آفلاین معتبر نیست.", Toast.LENGTH_LONG).show()
            return
        }
        downloadButton?.isEnabled = false
        downloadStatus?.text = "در حال آماده‌سازی مدل ${entry.first} برای ترجمه به فارسی…"
        modelExecutor.execute {
            var translator: Translator? = null
            try {
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()
                translator = Translation.getClient(options)
                activeDownload = translator
                Tasks.await(
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build()),
                    180, java.util.concurrent.TimeUnit.SECONDS
                )
                runOnUiThread {
                    downloadStatus?.text = "مدل ${entry.first} با موفقیت دانلود شد؛ ترجمهٔ این زبان به فارسی می‌تواند آفلاین کار کند."
                    Toast.makeText(this, "مدل ${entry.first} آمادهٔ استفادهٔ آفلاین است.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    downloadStatus?.text = "دانلود ناموفق بود. اینترنت را بررسی کن و دوباره تلاش کن. جزئیات: ${e.cause?.message ?: e.message ?: "خطای نامشخص"}"
                }
            } finally {
                translator?.close()
                if (activeDownload === translator) activeDownload = null
                runOnUiThread { downloadButton?.isEnabled = true }
            }
        }
    }

    @Deprecated("Uses the system screen-capture consent flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                putExtra(OverlayService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(this, "دکمهٔ شناور فعال شد. برای ترجمه آن را لمس کن.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onDestroy() {
        activeDownload?.close()
        modelExecutor.shutdownNow()
        super.onDestroy()
    }
}
