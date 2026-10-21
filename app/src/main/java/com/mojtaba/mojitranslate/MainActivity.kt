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
            text = "ترجمهٔ متن قابل‌شناسایی روی صفحه به فارسی. تشخیص متن از چند خط نوشتاری رایج پشتیبانی می‌کند؛ فونت‌های تزئینی، متن بسیار ریز، پس‌زمینه‌های پیچیده و برخی زبان‌ها ممکن است درست تشخیص داده نشوند. ابتدا ترجمهٔ آنلاین رایگان امتحان می‌شود و در صورت شکست، ترجمهٔ آفلاینِ پشتیبانی‌شده به کار می‌رود. برای استفاده روی بازی‌ها، مجوز ضبط صفحه و نمایش روی برنامه‌های دیگر لازم است."
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
            text = "ترجمهٔ آنلاین از رابط غیررسمی Google Translate استفاده می‌کند؛ دسترس‌پذیری و کیفیت آن تضمین رسمی ندارد. ترجمهٔ آفلاین به زبان پشتیبانی‌شده و مدل موجود روی گوشی وابسته است. برنامه نمی‌تواند تضمین کند همهٔ فونت‌ها، جلوه‌ها و زبان‌ها بی‌نقص خوانده شوند. صفحه‌های محافظت‌شده ممکن است قابل ضبط نباشند."
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
            Toast.makeText(this, "دکمهٔ شناور فعال شد. برای ترجمهٔ آنلاین، آن را لمس کن.", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
