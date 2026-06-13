package xyz.aprildown.timer.app.base.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ChineseNumberUtilsTest {

    @Test
    fun `toChineseDigits converts single digit correctly`() {
        assertEquals("零", ChineseNumberUtils.toChineseDigits(0))
        assertEquals("一", ChineseNumberUtils.toChineseDigits(1))
        assertEquals("五", ChineseNumberUtils.toChineseDigits(5))
        assertEquals("九", ChineseNumberUtils.toChineseDigits(9))
    }

    @Test
    fun `toChineseDigits converts multiple digits correctly`() {
        assertEquals("一零", ChineseNumberUtils.toChineseDigits(10))
        assertEquals("一三五", ChineseNumberUtils.toChineseDigits(135))
        assertEquals("九九九", ChineseNumberUtils.toChineseDigits(999))
    }

    @Test
    fun `toChineseDigits handles numbers with zeros`() {
        assertEquals("一零零", ChineseNumberUtils.toChineseDigits(100))
        assertEquals("二零二", ChineseNumberUtils.toChineseDigits(202))
        assertEquals("三零四零", ChineseNumberUtils.toChineseDigits(3040))
    }
}
