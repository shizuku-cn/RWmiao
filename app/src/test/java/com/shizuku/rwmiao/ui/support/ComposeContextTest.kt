package com.shizuku.rwmiao.ui.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ComposeContextTest {
    @Test
    fun accessibilityFallbackCoversEveryStringUsedByComposeDelegate() {
        val resourceIds = listOf(
            androidx.compose.ui.R.string.state_on,
            androidx.compose.ui.R.string.state_off,
            androidx.compose.ui.R.string.indeterminate,
            androidx.compose.ui.R.string.selected,
            androidx.compose.ui.R.string.not_selected,
            androidx.compose.ui.R.string.template_percent,
            androidx.compose.ui.R.string.in_progress,
            androidx.compose.ui.R.string.state_empty,
            androidx.compose.ui.R.string.tab,
            androidx.compose.ui.R.string.switch_role
        )

        resourceIds.forEach { id ->
            assertNotNull(composeAccessibilityText(id, "zh"))
            assertNotNull(composeAccessibilityText(id, "en"))
        }
    }

    @Test
    fun accessibilityFallbackUsesChineseAndEnglishLabels() {
        assertEquals(
            "关闭",
            composeAccessibilityText(androidx.compose.ui.R.string.state_off, "zh-CN")
        )
        assertEquals(
            "Off",
            composeAccessibilityText(androidx.compose.ui.R.string.state_off, "en-US")
        )
        assertNull(composeAccessibilityText(0, "zh-CN"))
    }
}
