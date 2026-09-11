package com.shizuku.rwmiao.ui.main

import android.content.Context
import com.shizuku.rwmiao.config.SettingsContract.KEY_UI_AUTO_CHECK_UPDATE
import com.shizuku.rwmiao.config.SettingsContract.PREFS_NAME
import com.shizuku.rwmiao.module.ModuleUpdateStateBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object ModuleStartupUpdate {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JvmStatic
    fun start(context: Context?) {
        if (context == null) return
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(KEY_UI_AUTO_CHECK_UPDATE, true)) return
        if (!ModuleUpdateStateBus.begin()) return

        ModuleUpdateStateBus.checking()
        scope.launch {
            try {
                when (val result = GithubUpdateManager().checkLatest()) {
                    UpdateCheckResult.UpToDate -> ModuleUpdateStateBus.upToDate()
                    is UpdateCheckResult.Available -> ModuleUpdateStateBus.available(
                        result.update.version,
                        result.update.downloadUrl,
                        result.update.sizeBytes,
                        result.update.releaseUrl,
                        result.update.releaseNotes
                    )
                    is UpdateCheckResult.Error -> ModuleUpdateStateBus.failed(result.message)
                }
            } catch (t: Throwable) {
                ModuleUpdateStateBus.failed(t.message?.takeIf { it.isNotBlank() } ?: "网络连接失败")
            }
        }
    }
}
