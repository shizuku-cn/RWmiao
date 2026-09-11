package com.shizuku.rwmiao.ui.support

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import com.shizuku.rwmiao.BuildConfig
import java.util.Locale

/**
 * Compose's Android accessibility bridge resolves its internal string resource
 * IDs through the resources of the ComposeView's Context. Runtime panels are
 * created inside the game process, so using the game's Activity directly makes
 * those IDs point at the game's resource table instead of RWmiao's table.
 */
internal fun moduleComposeContext(hostContext: Context): Context {
    if (hostContext.packageName == BuildConfig.APPLICATION_ID) return hostContext

    val installedModuleContext = runCatching {
        val moduleContext = hostContext.createPackageContext(
            BuildConfig.APPLICATION_ID,
            Context.CONTEXT_IGNORE_SECURITY
        )
        moduleContext.createConfigurationContext(hostContext.resources.configuration)
    }.getOrNull()

    if (installedModuleContext != null &&
        hasCompatibleComposeResources(installedModuleContext.resources)
    ) {
        return installedModuleContext
    }

    // NPatch can embed the module code without installing the RWmiao package.
    // Keep the host Context for services and preferences, but intercept the
    // small set of Compose accessibility strings that otherwise resolve
    // against the game's unrelated 0x7f resource table.
    return EmbeddedComposeContext(hostContext)
}

private fun hasCompatibleComposeResources(resources: Resources): Boolean = runCatching {
    resources.getResourceEntryName(androidx.compose.ui.R.string.state_off) == "state_off" &&
        resources.getString(androidx.compose.ui.R.string.state_off).isNotEmpty()
}.getOrDefault(false)

private class EmbeddedComposeContext(base: Context) : ContextWrapper(base) {
    private val accessibilityResources = ComposeAccessibilityResources(base.resources)

    override fun getResources(): Resources = accessibilityResources
}

@Suppress("DEPRECATION")
private class ComposeAccessibilityResources(
    private val hostResources: Resources
) : Resources(
    hostResources.assets,
    hostResources.displayMetrics,
    hostResources.configuration
) {
    override fun getText(id: Int): CharSequence {
        return fallbackText(id) ?: hostResources.getText(id)
    }

    override fun getString(id: Int): String {
        return fallbackText(id) ?: hostResources.getString(id)
    }

    override fun getString(id: Int, vararg formatArgs: Any): String {
        val fallback = fallbackText(id)
            ?: return hostResources.getString(id, *formatArgs)
        return String.format(currentLocale(), fallback, *formatArgs)
    }

    private fun fallbackText(id: Int): String? {
        return composeAccessibilityText(id, currentLocale().language)
    }

    private fun currentLocale(): Locale {
        val locales = hostResources.configuration.locales
        return if (locales.isEmpty) Locale.getDefault() else locales[0]
    }
}

internal fun composeAccessibilityText(id: Int, language: String): String? {
    val chinese = language.lowercase(Locale.ROOT).startsWith("zh")
    return when (id) {
        androidx.compose.ui.R.string.state_on -> if (chinese) "开启" else "On"
        androidx.compose.ui.R.string.state_off -> if (chinese) "关闭" else "Off"
        androidx.compose.ui.R.string.indeterminate -> if (chinese) "部分选中" else "Partially checked"
        androidx.compose.ui.R.string.selected -> if (chinese) "已选择" else "Selected"
        androidx.compose.ui.R.string.not_selected -> if (chinese) "未选择" else "Not selected"
        androidx.compose.ui.R.string.template_percent -> if (chinese) "%1\$d%%" else "%1\$d percent."
        androidx.compose.ui.R.string.in_progress -> if (chinese) "进行中" else "In progress"
        androidx.compose.ui.R.string.state_empty -> if (chinese) "空" else "Empty"
        androidx.compose.ui.R.string.tab -> if (chinese) "标签页" else "Tab"
        androidx.compose.ui.R.string.switch_role -> if (chinese) "开关" else "Switch"
        else -> null
    }
}
