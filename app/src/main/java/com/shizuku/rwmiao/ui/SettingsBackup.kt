package com.shizuku.rwmiao.ui

import android.app.Activity
import android.app.Fragment
import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import com.shizuku.rwmiao.config.SettingsContract.KEY_SCRIPT_ENABLED_PREFIX
import com.shizuku.rwmiao.config.SettingsContract.KEY_SCRIPTS_MASTER
import com.shizuku.rwmiao.ui.main.SettingsPage
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object SettingsBackup {
    private const val FORMAT = "rwmiao-settings"
    private const val SCHEMA = 1
    private const val TYPE = "type"
    private const val VALUE = "value"
    private const val SCRIPT_SETTING_PREFIX = "rwmiao_script_setting_"

    fun write(
        preferences: SharedPreferences,
        resolver: ContentResolver,
        uri: Uri
    ) {
        val output = resolver.openOutputStream(uri)
            ?: throw IOException("无法打开配置文件")
        output.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            writer.write(encode(preferences))
        }
    }

    fun read(
        preferences: SharedPreferences,
        resolver: ContentResolver,
        uri: Uri
    ) {
        val input = resolver.openInputStream(uri)
            ?: throw IOException("无法打开配置文件")
        val json = input.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        apply(preferences, json)
    }

    private fun encode(preferences: SharedPreferences): String {
        val current = preferences.all
        val values = JSONObject()
        current.keys
            .filterNot(::isScriptKey)
            .sorted()
            .forEach { key ->
                val value = current[key]
                    ?: throw IOException("配置项为空: $key")
                values.put(key, encodeValue(value))
            }
        return JSONObject()
            .put("format", FORMAT)
            .put("schema", SCHEMA)
            .put("preferences", values)
            .toString(2)
    }

    private fun encodeValue(value: Any): JSONObject {
        val encoded = JSONObject()
        when (value) {
            is Boolean -> encoded.put(TYPE, "boolean").put(VALUE, value)
            is Int -> encoded.put(TYPE, "int").put(VALUE, value)
            is Long -> encoded.put(TYPE, "long").put(VALUE, value)
            is Float -> encoded.put(TYPE, "float").put(VALUE, value.toDouble())
            is String -> encoded.put(TYPE, "string").put(VALUE, value)
            is Set<*> -> {
                val values = JSONArray()
                value.forEach { item ->
                    if (item !is String) throw IOException("配置集合包含非文本项")
                    values.put(item)
                }
                encoded.put(TYPE, "stringSet").put(VALUE, values)
            }
            else -> throw IOException("不支持的配置类型: ${value.javaClass.name}")
        }
        return encoded
    }

    private fun apply(preferences: SharedPreferences, json: String) {
        val root = try {
            JSONObject(json)
        } catch (error: Throwable) {
            throw IOException("配置文件不是有效 JSON", error)
        }
        if (root.optString("format") != FORMAT || root.optInt("schema", SCHEMA) < SCHEMA) {
            throw IOException("配置文件格式不受支持")
        }
        val values = root.optJSONObject("preferences")
            ?: throw IOException("配置文件缺少配置项")
        val decoded = LinkedHashMap<String, Any>()
        val keys = values.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (isScriptKey(key)) continue
            val value = values.optJSONObject(key)
                ?: throw IOException("配置项格式错误: $key")
            if (value.optString(TYPE) !in SUPPORTED_TYPES) continue
            decoded[key] = decodeValue(key, value)
        }

        val editor = preferences.edit()
        preferences.all.keys
            .filterNot(::isScriptKey)
            .forEach { key -> editor.remove(key) }
        decoded.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
                else -> throw IOException("配置项类型错误: $key")
            }
        }
        if (!editor.commit()) throw IOException("配置保存失败")
    }

    private fun decodeValue(key: String, encoded: JSONObject): Any {
        return try {
            when (encoded.optString(TYPE)) {
                "boolean" -> encoded.getBoolean(VALUE)
                "int" -> encoded.getInt(VALUE)
                "long" -> encoded.getLong(VALUE)
                "float" -> encoded.getDouble(VALUE).toFloat()
                "string" -> encoded.getString(VALUE)
                "stringSet" -> decodeStringSet(encoded.getJSONArray(VALUE))
                else -> throw IOException("配置项类型不受支持: $key")
            }
        } catch (error: IOException) {
            throw error
        } catch (error: Throwable) {
            throw IOException("配置项值错误: $key", error)
        }
    }

    private fun decodeStringSet(array: JSONArray): Set<String> {
        val values = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            val value = array.get(index)
            if (value !is String) throw IOException("配置集合包含非文本项")
            values.add(value)
        }
        return values
    }

    private fun isScriptKey(key: String): Boolean {
        return key == KEY_SCRIPTS_MASTER
                || key.startsWith(KEY_SCRIPT_ENABLED_PREFIX)
                || key.startsWith(SCRIPT_SETTING_PREFIX)
    }

    private val SUPPORTED_TYPES = setOf(
        "boolean",
        "int",
        "long",
        "float",
        "string",
        "stringSet"
    )
}

internal object ConfigurationFilePicker {
    private const val TAG = "rwmiao.configuration.file"
    private const val REQUEST_CODE = 4307

    fun import(page: SettingsPage, onImported: () -> Unit) {
        launch(page, importing = true, onImported = onImported)
    }

    fun export(page: SettingsPage) {
        launch(page, importing = false, onImported = null)
    }

    private fun launch(
        page: SettingsPage,
        importing: Boolean,
        onImported: (() -> Unit)?
    ) {
        val activity = page.hostActivity
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            val fragmentManager = activity.fragmentManager
            if (fragmentManager.findFragmentByTag(TAG) != null) return

            val fragment = ConfigurationFileFragment().apply {
                targetPage = page
                this.importing = importing
                this.onImported = onImported
            }
            fragmentManager.beginTransaction()
                .add(fragment, TAG)
                .commitAllowingStateLoss()
            fragmentManager.executePendingTransactions()

            val intent = Intent(
                if (importing) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_CREATE_DOCUMENT
            ).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (importing) "*/*" else "application/json"
                if (!importing) {
                    val timestamp = SimpleDateFormat(
                        "yyyy_MM_dd",
                        Locale.CHINA
                    ).format(Date())
                    putExtra(Intent.EXTRA_TITLE, "RW miao:$timestamp.json")
                }
            }
            fragment.startActivityForResult(intent, REQUEST_CODE)
        } catch (error: Throwable) {
            Toast.makeText(
                activity,
                error.message ?: if (importing) "导入配置失败" else "导出配置失败",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    class ConfigurationFileFragment : Fragment() {
        var targetPage: SettingsPage? = null
        var importing: Boolean = false
        var onImported: (() -> Unit)? = null

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)
            if (requestCode != REQUEST_CODE) return

            val page = targetPage
            val uri = data?.data
            if (resultCode == Activity.RESULT_OK && page != null && uri != null) {
                try {
                    if (importing) {
                        SettingsBackup.read(page.preferences, page.hostActivity.contentResolver, uri)
                        onImported?.invoke()
                        Toast.makeText(page.hostActivity, "配置已导入", Toast.LENGTH_SHORT).show()
                    } else {
                        SettingsBackup.write(page.preferences, page.hostActivity.contentResolver, uri)
                        Toast.makeText(page.hostActivity, "配置已导出", Toast.LENGTH_SHORT).show()
                    }
                } catch (error: Throwable) {
                    Toast.makeText(
                        page.hostActivity,
                        error.message ?: if (importing) "导入配置失败" else "导出配置失败",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            fragmentManager?.beginTransaction()
                ?.remove(this)
                ?.commitAllowingStateLoss()
        }
    }
}
