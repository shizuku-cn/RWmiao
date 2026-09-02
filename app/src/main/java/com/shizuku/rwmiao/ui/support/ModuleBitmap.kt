package com.shizuku.rwmiao.ui.support

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.shizuku.rwmiao.BuildConfig
import com.shizuku.rwmiao.R
import kotlin.math.roundToInt

internal fun loadModuleBitmap(context: Context, resourceId: Int): ImageBitmap? {
    return runCatching {
        val moduleContext = context.createPackageContext(
            BuildConfig.APPLICATION_ID,
            Context.CONTEXT_IGNORE_SECURITY
        )
        val drawable = moduleContext.getDrawable(resourceId) ?: return@runCatching null
        val sourceWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: 256
        val sourceHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: 256
        val scale = minOf(1f, 512f / maxOf(sourceWidth, sourceHeight).toFloat())
        val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        var visible = false
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) != 0) {
                    visible = true
                    break
                }
            }
            if (visible) break
        }
        if (!visible) return@runCatching null
        bitmap.asImageBitmap()
    }.getOrNull()
}

private fun loadModuleAssetBitmap(context: Context, fileName: String): ImageBitmap? {
    return runCatching {
        val moduleContext = if (context.packageName == BuildConfig.APPLICATION_ID) {
            context
        } else {
            context.createPackageContext(
                BuildConfig.APPLICATION_ID,
                Context.CONTEXT_IGNORE_SECURITY
            )
        }
        moduleContext.assets.open("module_logo/$fileName").use { input ->
            BitmapFactory.decodeStream(input)?.asImageBitmap()
                ?: return@runCatching null
        }
    }.getOrNull()
}

@Composable
internal fun ModuleLogo(
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val context = LocalContext.current
    val body = remember(context) {
        loadModuleBitmap(context, R.drawable.component_8_logo_body)
            ?: loadModuleAssetBitmap(context, "body.png")
            ?: loadEmbeddedModuleBitmap(highlights = false)
    }
    val highlights = remember(context) {
        loadModuleBitmap(context, R.drawable.component_8_logo_highlights)
            ?: loadModuleAssetBitmap(context, "highlights.png")
            ?: loadEmbeddedModuleBitmap(highlights = true)
    }
    val fallback = remember(context) {
        loadModuleBitmap(context, R.drawable.component_8_logo)
            ?: loadModuleAssetBitmap(context, "logo.png")
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (body != null && highlights != null) {
            Image(
                bitmap = body,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxSize()
            )
            Image(
                bitmap = highlights,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimary),
                modifier = Modifier.fillMaxSize()
            )
        } else if (fallback != null) {
            Image(
                bitmap = fallback,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else if (body != null) {
            Image(
                bitmap = body,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
