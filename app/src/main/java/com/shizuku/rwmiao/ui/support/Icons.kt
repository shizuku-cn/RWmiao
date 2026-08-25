package com.shizuku.rwmiao.ui.support

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object Icons {
    val assist: ImageVector by lazy {
        icon("assist") {
            moveTo(3f, 5f)
            lineTo(13f, 5f)
            lineTo(13f, 7f)
            lineTo(3f, 7f)
            close()
            moveTo(17f, 5f)
            lineTo(21f, 5f)
            lineTo(21f, 7f)
            lineTo(17f, 7f)
            close()
            moveTo(7f, 4f)
            lineTo(9f, 4f)
            lineTo(9f, 8f)
            lineTo(7f, 8f)
            close()
            moveTo(3f, 11f)
            lineTo(7f, 11f)
            lineTo(7f, 13f)
            lineTo(3f, 13f)
            close()
            moveTo(11f, 11f)
            lineTo(21f, 11f)
            lineTo(21f, 13f)
            lineTo(11f, 13f)
            close()
            moveTo(15f, 10f)
            lineTo(17f, 10f)
            lineTo(17f, 14f)
            lineTo(15f, 14f)
            close()
            moveTo(3f, 17f)
            lineTo(15f, 17f)
            lineTo(15f, 19f)
            lineTo(3f, 19f)
            close()
            moveTo(19f, 17f)
            lineTo(21f, 17f)
            lineTo(21f, 19f)
            lineTo(19f, 19f)
            close()
        }
    }

    val draw: ImageVector by lazy {
        icon("draw") {
            moveTo(3f, 17.25f)
            lineTo(3f, 21f)
            lineTo(6.75f, 21f)
            lineTo(17.81f, 9.94f)
            lineTo(14.06f, 6.19f)
            close()
            moveTo(20.71f, 7.04f)
            lineTo(18.37f, 4.7f)
            curveTo(17.98f, 4.31f, 17.35f, 4.31f, 16.96f, 4.7f)
            lineTo(15.13f, 6.53f)
            lineTo(18.88f, 10.28f)
            lineTo(20.71f, 8.45f)
            curveTo(21.1f, 8.06f, 21.1f, 7.43f, 20.71f, 7.04f)
            close()
            moveTo(5f, 19f)
            lineTo(13.74f, 10.26f)
            lineTo(13.82f, 10.34f)
            lineTo(5f, 19f)
            close()
        }
    }

    val environment: ImageVector by lazy {
        icon("environment") {
            moveTo(12f, 2f)
            curveTo(7.03f, 2f, 3f, 6.03f, 3f, 11f)
            curveTo(3f, 15.97f, 7.03f, 20f, 12f, 20f)
            curveTo(12.34f, 20f, 12.67f, 19.98f, 13f, 19.94f)
            lineTo(13f, 18f)
            curveTo(12.67f, 18.04f, 12.34f, 18.06f, 12f, 18.06f)
            curveTo(8.13f, 18.06f, 5f, 14.93f, 5f, 11f)
            curveTo(5f, 7.13f, 8.13f, 4f, 12f, 4f)
            curveTo(15.87f, 4f, 19f, 7.13f, 19f, 11f)
            curveTo(19f, 11.34f, 18.98f, 11.67f, 18.94f, 12f)
            lineTo(20.94f, 12f)
            curveTo(20.98f, 11.67f, 21f, 11.34f, 21f, 11f)
            curveTo(21f, 6.03f, 16.97f, 2f, 12f, 2f)
            close()
            moveTo(12f, 7f)
            lineTo(10f, 11f)
            lineTo(12f, 15f)
            lineTo(14f, 11f)
            close()
            moveTo(18f, 13f)
            lineTo(16f, 13f)
            lineTo(16f, 15f)
            lineTo(18f, 15f)
            close()
        }
    }

    val script: ImageVector by lazy {
        icon("script") {
            moveTo(8.59f, 16.59f)
            lineTo(13.17f, 12f)
            lineTo(8.59f, 7.41f)
            lineTo(10f, 6f)
            lineTo(16f, 12f)
            lineTo(10f, 18f)
            close()
            moveTo(4f, 19f)
            lineTo(20f, 19f)
            lineTo(20f, 21f)
            lineTo(4f, 21f)
            close()
        }
    }

    val ai: ImageVector by lazy {
        icon("ai") {
            moveTo(12f, 2f)
            lineTo(13.4f, 7.6f)
            lineTo(19f, 9f)
            lineTo(13.4f, 10.4f)
            lineTo(12f, 16f)
            lineTo(10.6f, 10.4f)
            lineTo(5f, 9f)
            lineTo(10.6f, 7.6f)
            close()
            moveTo(19f, 14f)
            lineTo(19.8f, 17.2f)
            lineTo(23f, 18f)
            lineTo(19.8f, 18.8f)
            lineTo(19f, 22f)
            lineTo(18.2f, 18.8f)
            lineTo(15f, 18f)
            lineTo(18.2f, 17.2f)
            close()
            moveTo(5f, 13f)
            lineTo(5.7f, 15.3f)
            lineTo(8f, 16f)
            lineTo(5.7f, 16.7f)
            lineTo(5f, 19f)
            lineTo(4.3f, 16.7f)
            lineTo(2f, 16f)
            lineTo(4.3f, 15.3f)
            close()
        }
    }

    val module: ImageVector by lazy {
        icon("module") {
            moveTo(12f, 2f)
            lineTo(21f, 7f)
            lineTo(21f, 17f)
            lineTo(12f, 22f)
            lineTo(3f, 17f)
            lineTo(3f, 7f)
            close()
            moveTo(5f, 8.2f)
            lineTo(5f, 15.8f)
            lineTo(11f, 19.13f)
            lineTo(11f, 11.53f)
            close()
            moveTo(13f, 11.53f)
            lineTo(13f, 19.13f)
            lineTo(19f, 15.8f)
            lineTo(19f, 8.2f)
            close()
            moveTo(12f, 4.3f)
            lineTo(6.1f, 7.58f)
            lineTo(12f, 10.86f)
            lineTo(17.9f, 7.58f)
            close()
        }
    }

    val close: ImageVector by lazy {
        icon("close") {
            moveTo(18.3f, 5.71f)
            lineTo(12f, 12f)
            lineTo(18.3f, 18.29f)
            lineTo(16.88f, 19.71f)
            lineTo(10.59f, 13.4f)
            lineTo(4.29f, 19.7f)
            lineTo(2.87f, 18.29f)
            lineTo(9.17f, 12f)
            lineTo(2.87f, 5.71f)
            lineTo(4.29f, 4.29f)
            lineTo(10.59f, 10.6f)
            lineTo(16.89f, 4.29f)
            close()
        }
    }

    val save: ImageVector by lazy {
        icon("save") {
            moveTo(17f, 3f)
            lineTo(5f, 3f)
            curveTo(3.9f, 3f, 3f, 3.9f, 3f, 5f)
            lineTo(3f, 19f)
            curveTo(3f, 20.1f, 3.9f, 21f, 5f, 21f)
            lineTo(19f, 21f)
            curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
            lineTo(21f, 7f)
            close()
            moveTo(12f, 19f)
            curveTo(10.34f, 19f, 9f, 17.66f, 9f, 16f)
            curveTo(9f, 14.34f, 10.34f, 13f, 12f, 13f)
            curveTo(13.66f, 13f, 15f, 14.34f, 15f, 16f)
            curveTo(15f, 17.66f, 13.66f, 19f, 12f, 19f)
            close()
            moveTo(6f, 5f)
            lineTo(15f, 5f)
            lineTo(15f, 9f)
            lineTo(6f, 9f)
            close()
        }
    }

    val import: ImageVector by lazy {
        icon("import") {
            moveTo(19f, 9f)
            lineTo(15f, 9f)
            lineTo(15f, 3f)
            lineTo(9f, 3f)
            lineTo(9f, 9f)
            lineTo(5f, 9f)
            lineTo(12f, 16f)
            close()
            moveTo(5f, 18f)
            lineTo(19f, 18f)
            lineTo(19f, 20f)
            lineTo(5f, 20f)
            close()
        }
    }

    val delete: ImageVector by lazy {
        icon("delete") {
            moveTo(6f, 7f)
            lineTo(18f, 7f)
            lineTo(18f, 19f)
            curveTo(18f, 20.1f, 17.1f, 21f, 16f, 21f)
            lineTo(8f, 21f)
            curveTo(6.9f, 21f, 6f, 20.1f, 6f, 19f)
            close()
            moveTo(15.5f, 4f)
            lineTo(14.5f, 3f)
            lineTo(9.5f, 3f)
            lineTo(8.5f, 4f)
            lineTo(5f, 4f)
            lineTo(5f, 6f)
            lineTo(19f, 6f)
            lineTo(19f, 4f)
            close()
        }
    }

    val settings: ImageVector by lazy {
        icon("settings") {
            moveTo(4f, 6f); lineTo(20f, 6f); lineTo(20f, 8f); lineTo(4f, 8f); close()
            moveTo(8f, 4f); lineTo(11f, 4f); lineTo(11f, 10f); lineTo(8f, 10f); close()
            moveTo(4f, 16f); lineTo(20f, 16f); lineTo(20f, 18f); lineTo(4f, 18f); close()
            moveTo(14f, 14f); lineTo(17f, 14f); lineTo(17f, 20f); lineTo(14f, 20f); close()
        }
    }

    val elephant: ImageVector by lazy {
        icon("elephant") {
            moveTo(4f, 20f)
            lineTo(4f, 10.5f)
            curveTo(4f, 5.8f, 7.1f, 3f, 11.7f, 3f)
            curveTo(16.2f, 3f, 19f, 6.1f, 19f, 10.6f)
            lineTo(19f, 14.1f)
            curveTo(19f, 15.5f, 19.7f, 16.2f, 20.5f, 16.2f)
            curveTo(21.4f, 16.2f, 21.8f, 15.3f, 21.8f, 14.2f)
            lineTo(21.8f, 13.1f)
            curveTo(22.6f, 13.1f, 23f, 13.8f, 23f, 14.8f)
            curveTo(23f, 17.2f, 21.8f, 18.6f, 20f, 18.6f)
            curveTo(17.9f, 18.6f, 16.4f, 16.9f, 16.4f, 14.6f)
            lineTo(16.4f, 11.4f)
            curveTo(16.4f, 9.4f, 15.2f, 8f, 13.6f, 7.3f)
            curveTo(14.2f, 8.2f, 14.4f, 9.2f, 14.4f, 10.3f)
            curveTo(14.4f, 13.2f, 12.6f, 14.7f, 10.2f, 14.7f)
            curveTo(7.5f, 14.7f, 5.8f, 12.9f, 5.8f, 10.3f)
            curveTo(5.8f, 7.9f, 7.1f, 6f, 9.2f, 5.3f)
            curveTo(7.6f, 5.8f, 6.7f, 7.4f, 6.7f, 9.6f)
            curveTo(6.7f, 11.4f, 7.8f, 12.4f, 9.3f, 12.4f)
            curveTo(10.7f, 12.4f, 11.4f, 11.4f, 11.4f, 10.4f)
            curveTo(11.4f, 9.5f, 12f, 9f, 12.7f, 9f)
            curveTo(13.1f, 9f, 13.5f, 9.2f, 13.8f, 9.5f)
            curveTo(13.5f, 6.8f, 11.5f, 5.4f, 9.1f, 5.4f)
            curveTo(7.1f, 5.4f, 5.8f, 7.1f, 5.8f, 10.6f)
            lineTo(5.8f, 20f)
            lineTo(9f, 20f)
            lineTo(9f, 16.8f)
            lineTo(14.1f, 16.8f)
            lineTo(14.1f, 20f)
            close()
            moveTo(17.1f, 7.8f)
            curveTo(17.1f, 8.5f, 17.7f, 9.1f, 18.4f, 9.1f)
            curveTo(19.1f, 9.1f, 19.7f, 8.5f, 19.7f, 7.8f)
            curveTo(19.7f, 7.1f, 19.1f, 6.5f, 18.4f, 6.5f)
            curveTo(17.7f, 6.5f, 17.1f, 7.1f, 17.1f, 7.8f)
            close()
        }
    }

    val github: ImageVector by lazy {
        icon("github") {
            moveTo(12f, 0.3f)
            curveTo(5.37f, 0.3f, 0f, 5.67f, 0f, 12.3f)
            curveTo(0f, 17.6f, 3.44f, 22.1f, 8.2f, 23.7f)
            curveTo(8.8f, 23.8f, 9f, 23.4f, 9f, 23.1f)
            curveTo(9f, 22.8f, 9f, 22f, 9f, 21.1f)
            curveTo(5.67f, 21.8f, 4.97f, 18.5f, 4.97f, 18.5f)
            curveTo(4.43f, 17.1f, 3.64f, 16.7f, 3.64f, 16.7f)
            curveTo(2.55f, 16f, 3.72f, 16f, 3.72f, 16f)
            curveTo(4.93f, 16.1f, 5.56f, 17.2f, 5.56f, 17.2f)
            curveTo(6.63f, 19f, 8.37f, 18.5f, 9.06f, 18.2f)
            curveTo(9.17f, 17.4f, 9.48f, 16.9f, 9.83f, 16.6f)
            curveTo(7.17f, 16.3f, 4.37f, 15.3f, 4.37f, 10.7f)
            curveTo(4.37f, 9.4f, 4.83f, 8.3f, 5.6f, 7.5f)
            curveTo(5.47f, 7.2f, 5.07f, 6f, 5.7f, 4.4f)
            curveTo(5.7f, 4.4f, 6.7f, 4.1f, 9f, 5.7f)
            curveTo(9.96f, 5.4f, 10.98f, 5.3f, 12f, 5.3f)
            curveTo(13.02f, 5.3f, 14.04f, 5.4f, 15f, 5.7f)
            curveTo(17.3f, 4.1f, 18.3f, 4.4f, 18.3f, 4.4f)
            curveTo(18.93f, 6f, 18.53f, 7.2f, 18.4f, 7.5f)
            curveTo(19.17f, 8.3f, 19.63f, 9.4f, 19.63f, 10.7f)
            curveTo(19.63f, 15.3f, 16.83f, 16.3f, 14.17f, 16.6f)
            curveTo(14.57f, 17f, 14.92f, 17.7f, 14.92f, 18.8f)
            curveTo(14.92f, 20.4f, 14.9f, 21.7f, 14.9f, 23.1f)
            curveTo(14.9f, 23.4f, 15.1f, 23.8f, 15.7f, 23.7f)
            curveTo(20.56f, 22.1f, 24f, 17.6f, 24f, 12.3f)
            curveTo(24f, 5.67f, 18.63f, 0.3f, 12f, 0.3f)
            close()
        }
    }

    val refresh: ImageVector by lazy {
        icon("refresh") {
            moveTo(12f, 4f)
            curveTo(16.42f, 4f, 20f, 7.58f, 20f, 12f)
            lineTo(22f, 12f)
            lineTo(19f, 15f)
            lineTo(16f, 12f)
            lineTo(18f, 12f)
            curveTo(18f, 8.69f, 15.31f, 6f, 12f, 6f)
            curveTo(9.99f, 6f, 8.2f, 6.99f, 7.11f, 8.5f)
            lineTo(5.38f, 7.5f)
            curveTo(6.82f, 5.39f, 9.23f, 4f, 12f, 4f)
            close()
            moveTo(6f, 12f)
            curveTo(6f, 15.31f, 8.69f, 18f, 12f, 18f)
            curveTo(14.01f, 18f, 15.8f, 17.01f, 16.89f, 15.5f)
            lineTo(18.62f, 16.5f)
            curveTo(17.18f, 18.61f, 14.77f, 20f, 12f, 20f)
            curveTo(7.58f, 20f, 4f, 16.42f, 4f, 12f)
            lineTo(2f, 12f)
            lineTo(5f, 9f)
            lineTo(8f, 12f)
            close()
        }
    }

    val add: ImageVector by lazy {
        icon("add") {
            moveTo(11f, 5f)
            lineTo(13f, 5f)
            lineTo(13f, 11f)
            lineTo(19f, 11f)
            lineTo(19f, 13f)
            lineTo(13f, 13f)
            lineTo(13f, 19f)
            lineTo(11f, 19f)
            lineTo(11f, 13f)
            lineTo(5f, 13f)
            lineTo(5f, 11f)
            lineTo(11f, 11f)
            close()
        }
    }

    val remove: ImageVector by lazy {
        icon("remove") {
            moveTo(5f, 11f)
            lineTo(19f, 11f)
            lineTo(19f, 13f)
            lineTo(5f, 13f)
            close()
        }
    }

    val infinite: ImageVector by lazy {
        icon("infinite") {
            moveTo(7.5f, 7f)
            curveTo(4.5f, 7f, 3f, 9.2f, 3f, 12f)
            curveTo(3f, 14.8f, 4.5f, 17f, 7.5f, 17f)
            curveTo(9.6f, 17f, 11.1f, 15.5f, 12f, 14.1f)
            curveTo(12.9f, 15.5f, 14.4f, 17f, 16.5f, 17f)
            curveTo(19.5f, 17f, 21f, 14.8f, 21f, 12f)
            curveTo(21f, 9.2f, 19.5f, 7f, 16.5f, 7f)
            curveTo(14.4f, 7f, 12.9f, 8.5f, 12f, 9.9f)
            curveTo(11.1f, 8.5f, 9.6f, 7f, 7.5f, 7f)
            close()
            moveTo(7.5f, 9f)
            curveTo(8.8f, 9f, 9.8f, 10.4f, 10.8f, 12f)
            curveTo(9.8f, 13.6f, 8.8f, 15f, 7.5f, 15f)
            curveTo(5.9f, 15f, 5f, 13.8f, 5f, 12f)
            curveTo(5f, 10.2f, 5.9f, 9f, 7.5f, 9f)
            close()
            moveTo(16.5f, 9f)
            curveTo(18.1f, 9f, 19f, 10.2f, 19f, 12f)
            curveTo(19f, 13.8f, 18.1f, 15f, 16.5f, 15f)
            curveTo(15.2f, 15f, 14.2f, 13.6f, 13.2f, 12f)
            curveTo(14.2f, 10.4f, 15.2f, 9f, 16.5f, 9f)
            close()
        }
    }

    val check: ImageVector by lazy {
        icon("check") {
            moveTo(9f, 16.17f)
            lineTo(4.83f, 12f)
            lineTo(3.41f, 13.41f)
            lineTo(9f, 19f)
            lineTo(21f, 7f)
            lineTo(19.59f, 5.59f)
            close()
        }
    }

    private fun icon(
        name: String,
        pathFillType: PathFillType = PathFillType.NonZero,
        block: PathBuilder.() -> Unit
    ): ImageVector {
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = pathFillType,
                pathBuilder = block
            )
        }.build()
    }
}
