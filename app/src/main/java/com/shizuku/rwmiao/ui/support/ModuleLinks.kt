package com.shizuku.rwmiao.ui.support

import android.content.Context
import android.content.Intent
import android.net.Uri

internal const val MODULE_GITHUB_URL = "https://github.com/shizuku-cn/RWmiao"
internal const val MODULE_RELEASES_URL = "https://github.com/shizuku-cn/RWmiao/releases/latest"
internal const val MODULE_BILIBILI_URL = "https://b23.tv/0c0Ehn2"
internal const val SHIZUKU_BILIBILI_URL = "https://space.bilibili.com/1710190893"
internal const val MODULE_QQ_GROUP_URL =
    "https://qun.qq.com/universal-share/share?ac=1&authKey=1jjPiANqolekHUQV%2FyCXtkXc1W6fC%2BPtLdq7l15X%2FwcqCc9UWumdLscLOkabvMk%2B&busi_data=eyJncm91cENvZGUiOiI1NDQ4MTIxNjMiLCJ0b2tlbiI6InVOclFEOTRoeDBqSFh3cUJ0WC9lenY3TDBlMEtXN0gwVFJxOHk2cmlZbHp2cmxiSHhkWGJUWnBPVE9BSlNrNHUiLCJ1aW4iOiIyODQzODMzMTcwIn0%3D&data=wE2KS1yQyEK3dyNqNn-NSXRi87bz3gGsW4eZ1bxaO-8mJ3Cpj9RIfuRRuRLAd8Dq0mtBQPyDA2J4vYLKNZ4fjg&svctype=4&tempid=h5_group_info"

internal const val LIBXPOSED_API_URL = "https://github.com/libxposed/api"
internal const val QUADFLASK_COLORPICKER_URL = "https://github.com/quadflask/colorpicker"
internal const val LUAJ_PROJECT_URL = "https://sourceforge.net/projects/luaj/"

internal fun openModuleLink(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
