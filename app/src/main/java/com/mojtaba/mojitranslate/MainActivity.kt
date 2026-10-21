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
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
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
    private var modeSpinner: Spinner? = null
    private var wifiOnlyCheck: CheckBox? = null
    private var overlayScaleSpinner: Spinner? = null
    private var overlayOpacitySpinner: Spinner? = null
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
            text = "ترجمهٔ متن روی صفحه به فارسی با سه حالت انتخابی: آنلاین، آفلاین یا خودکار. برای استفادهٔ آفلاین باید مدل زبان را از قبل دانلود کرده باشی."
            textSize = 16f
            setPadding(0, 20, 0, 20)
        })

        root.addView(TextView(this).apply {
            text = "حالت ترجمه"
            textSize = 19f
            setPadding(0, 12, 0, 8)
        })
        modeSpinner = Spinner(this).also { spinner ->
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                listOf("خودکار: آنلاین و سپس آفلاین", "فقط آنلاین", "فقط آفلاین"))
            val prefs = AppSettings.prefs(this)
            spinner.setSelection(when (prefs.getString(AppSettings.MODE, AppSettings.MODE_AUTO)) {
                AppSettings.MODE_ONLINE -> 1
                AppSettings.MODE_OFFLINE -> 2
                else -> 0
            })
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    val mode = when (position) { 1 -> AppSettings.MODE_ONLINE; 2 -> AppSettings.MODE_OFFLINE; else -> AppSettings.MODE_AUTO }
                    prefs.edit().putString(AppSettings.MODE, mode).apply()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
            root.addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        wifiOnlyCheck = CheckBox(this).apply {
            text = "فقط با Wi-Fi مدل‌های آفلاین دانلود شوند"
            isChecked = AppSettings.prefs(this@MainActivity).getBoolean(AppSettings.WIFI_ONLY, false)
            setOnCheckedChangeListener { _, checked -> AppSettings.prefs(this@MainActivity).edit().putBoolean(AppSettings.WIFI_ONLY, checked).apply() }
            root.addView(this)
        }
        root.addView(CheckBox(this).apply {
            text = "قبل از هر ترجمه، محدودهٔ متن را روی صفحه انتخاب کنم"
            isChecked = AppSettings.prefs(this@MainActivity).getBoolean("select_region_each_time", false)
            setOnCheckedChangeListener { _, checked ->
                AppSettings.prefs(this@MainActivity).edit().putBoolean("select_region_each_time", checked).apply()
            }
            setPadding(0, 4, 0, 4)
        })
        root.addView(TextView(this).apply {
            text = "ظاهر ترجمهٔ روی صفحه"
            textSize = 19f
            setPadding(0, 12, 0, 6)
        })
        overlayScaleSpinner = Spinner(this).also { spinner ->
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                listOf("اندازهٔ متن: کوچک‌تر", "اندازهٔ متن: معمولی", "اندازهٔ متن: بزرگ‌تر"))
            spinner.setSelection(when {
                AppSettings.prefs(this).getFloat(AppSettings.TEXT_SCALE, 1f) < 0.9f -> 0
                AppSettings.prefs(this).getFloat(AppSettings.TEXT_SCALE, 1f) > 1.1f -> 2
                else -> 1
            })
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    val scale = when (position) { 0 -> 0.82f; 2 -> 1.22f; else -> 1f }
                    AppSettings.prefs(this@MainActivity).edit().putFloat(AppSettings.TEXT_SCALE, scale).apply()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
            root.addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        overlayOpacitySpinner = Spinner(this).also { spinner ->
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                listOf("پوشش پس‌زمینه: شفاف‌تر", "پوشش پس‌زمینه: معمولی", "پوشش پس‌زمینه: مات‌تر"))
            spinner.setSelection(when (AppSettings.prefs(this).getInt(AppSettings.COVER_OPACITY, 230)) {
                in 0..179 -> 0
                in 241..255 -> 2
                else -> 1
            })
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    val alpha = when (position) { 0 -> 150; 2 -> 255; else -> 230 }
                    AppSettings.prefs(this@MainActivity).edit().putInt(AppSettings.COVER_OPACITY, alpha).apply()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
            root.addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

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
            text = "نمایش وضعیت مدل‌های دانلودشده"
            setOnClickListener { refreshDownloadedModels() }
        })
        root.addView(Button(this).apply {
            text = "حذف مدل زبان انتخاب‌شده"
            setOnClickListener { deleteSelectedModel() }
        })

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
            text = "حالت آفلاین هیچ اتصال ترجمهٔ اینترنتی برقرار نمی‌کند و فقط با مدل از قبل دانلودشده کار می‌کند. حالت خودکار ابتدا آنلاین را امتحان می‌کند و در صورت شکست به مدل آفلاین برمی‌گردد. پوشش پس‌زمینه تقریبی است و نمی‌تواند بافت پیچیدهٔ تصویر اصلی را بازسازی کند."
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
                val conditionsBuilder = DownloadConditions.Builder()
                if (AppSettings.prefs(this).getBoolean(AppSettings.WIFI_ONLY, false)) conditionsBuilder.requireWifi()
                Tasks.await(
                    translator.downloadModelIfNeeded(conditionsBuilder.build()),
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

    private fun refreshDownloadedModels() {
        downloadStatus?.text = "در حال بررسی مدل‌های دانلودشده…"
        modelExecutor.execute {
            try {
                val models = Tasks.await(
                    RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java),
                    30, java.util.concurrent.TimeUnit.SECONDS
                )
                val languagesByTag = languages.associate { it.second to it.first }
                val names = models.map { model ->
                    val tag = model.language
                    "${languagesByTag[tag] ?: tag} ($tag)"
                }.sorted()
                runOnUiThread {
                    downloadStatus?.text = if (names.isEmpty()) "مدل زبان ترجمه‌ای پیدا نشد. برای استفادهٔ آفلاین ابتدا یک زبان را دانلود کن."
                    else "مدل‌های زبان موجود: " + names.joinToString("، ") + "\nمدل مقصد فارسی ممکن است به‌صورت جداگانه در فهرست نمایش داده نشود."
                }
            } catch (e: Exception) {
                runOnUiThread { downloadStatus?.text = "خواندن فهرست مدل‌ها ناموفق بود: ${e.cause?.message ?: e.message ?: "خطای نامشخص"}" }
            }
        }
    }

    private fun deleteSelectedModel() {
        val index = languageSpinner?.selectedItemPosition ?: return
        val entry = languages.getOrNull(index) ?: return
        val language = TranslateLanguage.fromLanguageTag(entry.second) ?: return
        val model = TranslateRemoteModel.Builder(language).build()
        downloadButton?.isEnabled = false
        downloadStatus?.text = "در حال حذف مدل ${entry.first}…"
        modelExecutor.execute {
            try {
                Tasks.await(RemoteModelManager.getInstance().deleteDownloadedModel(model), 30, java.util.concurrent.TimeUnit.SECONDS)
                runOnUiThread { downloadStatus?.text = "درخواست حذف مدل ${entry.first} انجام شد. اگر این مدل دانلود نشده بود، تغییری رخ نمی‌دهد." }
            } catch (e: Exception) {
                runOnUiThread { downloadStatus?.text = "حذف مدل ناموفق بود: ${e.cause?.message ?: e.message ?: "خطای نامشخص"}" }
            } finally {
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
