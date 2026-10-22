package com.mojtaba.mojitranslate

/** Maps internal errors to fixed, privacy-safe messages without echoing URLs or recognized text. */
object TranslationFailureClassifier {
    fun userMessage(detail: String): String {
        val value = detail.lowercase()
        return when {
            value.contains("http 403") || value.contains("http 429") ->
                "سرویس ترجمه درخواست را نپذیرفت یا موقتاً محدود کرد؛ بعداً دوباره تلاش کن."
            value.contains("http") || value.contains("unknownhost") ||
                value.contains("timed out") || value.contains("connect") ||
                value.contains("network") || value.contains("socket") ->
                "اتصال به سرویس ترجمه ناموفق بود؛ اینترنت و دسترسی شبکه را بررسی کن."
            value.contains("مدل") ->
                "مدل ترجمهٔ آفلاین آماده نیست؛ وضعیت مدل‌های دانلودشده را در صفحهٔ اصلی بررسی کن."
            value.contains("زبان") ->
                "زبان متن شناسایی نشد یا مدل ترجمهٔ آن پشتیبانی نمی‌شود."
            else ->
                "ترجمه انجام نشد؛ سرویس آنلاین یا مدل آفلاین در دسترس نیست."
        }
    }
}
