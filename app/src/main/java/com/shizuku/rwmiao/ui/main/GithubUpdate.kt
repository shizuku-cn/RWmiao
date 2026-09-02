package com.shizuku.rwmiao.ui.main

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import com.shizuku.rwmiao.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class UpdateInfo(
    val version: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

internal sealed class UpdateCheckResult {
    object UpToDate : UpdateCheckResult()
    data class Available(val update: UpdateInfo) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

internal class GithubUpdateManager(private val context: Context) {
    suspend fun checkLatest(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val releaseResponse = fetchJson(LATEST_RELEASE_URL)
            val remote = if (releaseResponse.code in 200..299) {
                parseRelease(JSONObject(releaseResponse.body))
            } else if (releaseResponse.code == 404) {
                findRepositoryApk()
            } else {
                return@withContext UpdateCheckResult.Error(
                    "GitHub 请求失败：${releaseResponse.code}"
                )
            }
            if (remote == null) {
                return@withContext UpdateCheckResult.Error("GitHub 未找到可更新 APK")
            }
            val currentVersion = parseVersion(BuildConfig.VERSION_NAME)
                ?: return@withContext UpdateCheckResult.Error("当前版本号无法识别")
            if (remote.key <= currentVersion) {
                UpdateCheckResult.UpToDate
            } else {
                UpdateCheckResult.Available(
                    UpdateInfo(
                        version = remote.version,
                        downloadUrl = remote.downloadUrl,
                        sizeBytes = remote.sizeBytes
                    )
                )
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message?.takeIf { it.isNotBlank() } ?: "网络连接失败")
        }
    }

    suspend fun downloadAndInstall(update: UpdateInfo): Result<Unit> = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(DownloadManager::class.java)
            ?: return@withContext Result.failure(IllegalStateException("下载服务不可用"))
        val fileName = "module-update-${update.version.replace(UNSAFE_FILE_CHARS, "_")}.apk"
        val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
            .setTitle("模块更新")
            .setDescription(update.version)
            .setMimeType(APK_MIME_TYPE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                fileName
            )
        val id = try {
            manager.enqueue(request)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        try {
            for (attempt in 0 until DOWNLOAD_POLL_ATTEMPTS) {
                var status = DownloadManager.STATUS_PENDING
                var reason = 0
                manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    }
                }
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val uri = manager.getUriForDownloadedFile(id)
                            ?: return@withContext Result.failure(
                                IllegalStateException("下载文件不可用")
                            )
                        withContext(Dispatchers.Main) { openInstaller(uri) }
                        return@withContext Result.success(Unit)
                    }
                    DownloadManager.STATUS_FAILED -> {
                        return@withContext Result.failure(
                            IllegalStateException("下载失败：$reason")
                        )
                    }
                }
                delay(DOWNLOAD_POLL_INTERVAL_MS)
            }
            Result.failure(IllegalStateException("下载超时"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun openInstaller(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val moduleContext = runCatching {
            context.createPackageContext(
                BuildConfig.APPLICATION_ID,
                Context.CONTEXT_IGNORE_SECURITY
            )
        }.getOrNull()
        (moduleContext ?: context).startActivity(intent)
    }

    private fun parseRelease(release: JSONObject): RemoteCandidate? {
        val tag = release.optString("tag_name").ifBlank {
            release.optString("name")
        }.trim()
        val key = parseVersion(tag) ?: return null
        val asset = findApkAsset(release) ?: return null
        val url = asset.optString("browser_download_url")
        if (url.isBlank()) return null
        return RemoteCandidate(
            version = normalizeVersion(tag),
            key = key,
            downloadUrl = url,
            sizeBytes = asset.optLong("size", 0L)
        )
    }

    private fun findRepositoryApk(): RemoteCandidate? {
        val response = fetchJson(REPOSITORY_APK_URL)
        if (response.code !in 200..299) return null
        val files = org.json.JSONArray(response.body)
        var best: RemoteCandidate? = null
        for (index in 0 until files.length()) {
            val file = files.optJSONObject(index) ?: continue
            val name = file.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val key = parseVersion(name) ?: continue
            val url = file.optString("download_url")
            if (url.isBlank()) continue
            val candidate = RemoteCandidate(
                version = normalizeVersion(name),
                key = key,
                downloadUrl = url,
                sizeBytes = file.optLong("size", 0L)
            )
            if (best == null || candidate.key > best.key) best = candidate
        }
        return best
    }

    private fun findApkAsset(release: JSONObject): JSONObject? {
        val assets = release.optJSONArray("assets") ?: return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name").endsWith(".apk", ignoreCase = true) &&
                asset.optString("browser_download_url").isNotBlank()
            ) {
                return asset
            }
        }
        return null
    }

    private fun fetchJson(url: String): ApiResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", BuildConfig.APPLICATION_ID)
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }.orEmpty()
            ApiResponse(code, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeVersion(value: String): String {
        val match = VERSION_PATTERN.find(value) ?: return value.removePrefix("v")
        return match.value.removePrefix("v").removePrefix("V")
    }

    private fun parseVersion(value: String): VersionKey? {
        val match = VERSION_PATTERN.find(value) ?: return null
        return VersionKey(
            major = match.groupValues[1].toIntOrNull() ?: return null,
            minor = match.groupValues[2].toIntOrNull() ?: 0,
            patch = match.groupValues[3].toIntOrNull() ?: 0,
            revision = match.groupValues[4].toIntOrNull() ?: 0
        )
    }

    private data class VersionKey(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val revision: Int
    ) : Comparable<VersionKey> {
        override fun compareTo(other: VersionKey): Int =
            compareValuesBy(this, other, VersionKey::major, VersionKey::minor,
                VersionKey::patch, VersionKey::revision)
    }

    private data class ApiResponse(val code: Int, val body: String)

    private data class RemoteCandidate(
        val version: String,
        val key: VersionKey,
        val downloadUrl: String,
        val sizeBytes: Long
    )

    private companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/shizuku-cn/RWmiao/releases/latest"
        const val REPOSITORY_APK_URL =
            "https://api.github.com/repos/shizuku-cn/RWmiao/contents/%E5%8F%91%E5%B8%83"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val DOWNLOAD_POLL_INTERVAL_MS = 500L
        const val DOWNLOAD_POLL_ATTEMPTS = 360
        val VERSION_PATTERN = Regex("""[vV]?(\d+)(?:\.(\d+))?(?:[.-](\d+))?(?:[.-][rR](\d+))?""")
        val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}
