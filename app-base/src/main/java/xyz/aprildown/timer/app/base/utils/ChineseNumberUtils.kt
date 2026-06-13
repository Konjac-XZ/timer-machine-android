package xyz.aprildown.timer.app.base.utils

import java.util.Locale

/**
 * Utility for converting numbers to Chinese digit-by-digit format for TTS.
 *
 * In Chinese TTS, numbers should be pronounced digit-by-digit rather than as full numbers.
 * For example, "135" should be pronounced as "一三五" (yī sān wǔ) instead of
 * "一百三十五" (yībǎi sānshíwǔ).
 */
object ChineseNumberUtils {
    private val CHINESE_DIGITS = arrayOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")

    /**
     * Checks if the current locale is Chinese.
     */
    fun isChineseLocale(): Boolean = Locale.getDefault().language == "zh"

    /**
     * Converts a number to Chinese digit-by-digit format.
     *
     * @param number The number to convert
     * @return String with each digit converted to its Chinese character
     *
     * Example: 135 -> "一三五"
     */
    fun toChineseDigits(number: Int): String {
        return number.toString().map { CHINESE_DIGITS[it.digitToInt()] }.joinToString("")
    }
}
