package com.mojtaba.mojitranslate

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val captureRequest = 4107

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
            text = "ترجمهٔ متن روی صفحه به فارسی؛ بدون کلید API و بدون هزینهٔ سرویس ترجمه. برای استفاده روی بازی‌ها، مجوز ضبط صفحه و نمایش روی برنامه‌های دیگر لازم است."
            textSize = 16f
            setPadding(0, 20, 0, 20)
        })
        root.addView(Button(this).apply {
            text = "۱. اجازهٔ نمایش روی برنامه‌های دیگر"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                } else {
                    Toast.makeText(this@MainActivity, "این مجوز قبلاً فعال است.", Toast.LENGTH_SHORT).show()
                }
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
            text = "نکته: ترجمهٔ روی دستگاه رایگان است، اما کیفیت آن بسته به زبان و متن می‌تواند با Google Translate متفاوت باشد. بعضی صفحه‌های محافظت‌شده قابل ضبط نیستند."
            textSize = 13f
            setPadding(0, 20, 0, 0)
        })
        setContentView(root)
    }

    @Deprecated("Uses the system screen-capture consent flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                putExtra(OverlayService.EXTRA_RESULT_DATA, data)
            }
            androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(this, "دکمهٔ شناور فعال شد. برای ترجمه، آن را لمس کن.", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
