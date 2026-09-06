package com.shizuku.rwmiao.ui.main

import com.shizuku.rwmiao.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
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

internal class GithubUpdateManager {
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
        val VERSION_PATTERN = Regex("""[vV]?(\d+)(?:\.(\d+))?(?:[.-](\d+))?(?:[.-][rR](\d+))?""")
    }
}
