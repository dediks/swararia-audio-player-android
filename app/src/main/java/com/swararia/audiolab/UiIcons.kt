package com.swararia.audiolab

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** A single, local 24 dp icon family. No font glyphs or icon-library dependency. */
internal object UiIcons {
    val Library = outline("Library") {
        moveTo(5f, 4f); lineTo(9f, 4f); lineTo(9f, 20f); lineTo(5f, 20f); close()
        moveTo(14f, 4f); lineTo(18f, 4f); lineTo(18f, 20f); lineTo(14f, 20f); close()
    }
    val Plus = outline("Plus") {
        moveTo(12f, 4f); lineTo(12f, 20f); moveTo(4f, 12f); lineTo(20f, 12f)
    }
    val Search = outline("Search") {
        moveTo(17f, 10f); arcTo(7f, 7f, 0f, true, true, 3f, 10f)
        arcTo(7f, 7f, 0f, true, true, 17f, 10f)
        moveTo(15.2f, 15.2f); lineTo(21f, 21f)
    }
    val More = outline("More") {
        moveTo(12f, 5f); lineTo(12f, 5.2f)
        moveTo(12f, 11.9f); lineTo(12f, 12.1f)
        moveTo(12f, 18.8f); lineTo(12f, 19f)
    }
    val ChevronDown = outline("ChevronDown") {
        moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f)
    }
    val ChevronRight = outline("ChevronRight") {
        moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f)
    }
    val Back = outline("Back") {
        moveTo(15f, 4f); lineTo(7f, 12f); lineTo(15f, 20f)
    }
    val Close = outline("Close") {
        moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f)
    }
    val Play = filled("Play") {
        moveTo(7f, 4f); lineTo(20f, 12f); lineTo(7f, 20f); close()
    }
    val Pause = filled("Pause") {
        moveTo(6f, 4f); lineTo(10f, 4f); lineTo(10f, 20f); lineTo(6f, 20f); close()
        moveTo(14f, 4f); lineTo(18f, 4f); lineTo(18f, 20f); lineTo(14f, 20f); close()
    }
    val Previous = filled("Previous") {
        moveTo(4f, 4f); lineTo(6f, 4f); lineTo(6f, 20f); lineTo(4f, 20f); close()
        moveTo(20f, 4f); lineTo(7f, 12f); lineTo(20f, 20f); close()
    }
    val Next = filled("Next") {
        moveTo(18f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(18f, 20f); close()
        moveTo(4f, 4f); lineTo(17f, 12f); lineTo(4f, 20f); close()
    }
    val Stop = filled("Stop") {
        moveTo(6f, 6f); lineTo(18f, 6f); lineTo(18f, 18f); lineTo(6f, 18f); close()
    }
    val Queue = outline("Queue") {
        moveTo(4f, 6f); lineTo(4.2f, 6f); moveTo(9f, 6f); lineTo(21f, 6f)
        moveTo(4f, 12f); lineTo(4.2f, 12f); moveTo(9f, 12f); lineTo(21f, 12f)
        moveTo(4f, 18f); lineTo(4.2f, 18f); moveTo(9f, 18f); lineTo(21f, 18f)
    }
    val Import = outline("Import") {
        moveTo(4f, 15f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 15f)
        moveTo(12f, 3f); lineTo(12f, 14f)
        moveTo(7f, 9f); lineTo(12f, 14f); lineTo(17f, 9f)
    }
    val Download = outline("Download") {
        moveTo(5f, 20f); lineTo(19f, 20f)
        moveTo(12f, 3f); lineTo(12f, 15f)
        moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
    }
    val Link = outline("Link") {
        moveTo(9f, 15f); lineTo(15f, 9f)
        moveTo(8f, 10f); lineTo(5f, 13f); curveTo(1f, 17f, 7f, 23f, 11f, 19f); lineTo(14f, 16f)
        moveTo(10f, 8f); lineTo(13f, 5f); curveTo(17f, 1f, 23f, 7f, 19f, 11f); lineTo(16f, 14f)
    }
    val Settings = outline("Settings") {
        moveTo(15.3f, 12f); arcTo(3.3f, 3.3f, 0f, true, true, 8.7f, 12f)
        arcTo(3.3f, 3.3f, 0f, true, true, 15.3f, 12f)
        moveTo(18.5f, 12f); arcTo(6.5f, 6.5f, 0f, true, true, 5.5f, 12f)
        arcTo(6.5f, 6.5f, 0f, true, true, 18.5f, 12f)
        moveTo(12f, 2f); lineTo(12f, 5.5f); moveTo(12f, 18.5f); lineTo(12f, 22f)
        moveTo(2f, 12f); lineTo(5.5f, 12f); moveTo(18.5f, 12f); lineTo(22f, 12f)
        moveTo(5f, 5f); lineTo(7.4f, 7.4f); moveTo(16.6f, 16.6f); lineTo(19f, 19f)
        moveTo(5f, 19f); lineTo(7.4f, 16.6f); moveTo(16.6f, 7.4f); lineTo(19f, 5f)
    }
    val History = outline("History") {
        moveTo(4f, 10f); curveTo(5f, 3f, 14f, 1f, 19f, 6f)
        curveTo(25f, 13f, 17f, 25f, 8f, 20f)
        moveTo(4f, 4f); lineTo(4f, 10f); lineTo(10f, 10f)
        moveTo(12f, 7f); lineTo(12f, 12f); lineTo(16f, 14f)
    }
    val Rename = outline("Rename") {
        moveTo(4f, 16f); lineTo(4f, 20f); lineTo(8f, 20f); lineTo(20f, 8f)
        lineTo(16f, 4f); lineTo(4f, 16f); moveTo(13f, 7f); lineTo(17f, 11f)
    }
    val Info = outline("Info") {
        moveTo(21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f)
        arcTo(9f, 9f, 0f, true, true, 21f, 12f)
        moveTo(12f, 7f); lineTo(12f, 7.2f); moveTo(12f, 11f); lineTo(12f, 17f)
    }
    val Paste = outline("Paste") {
        moveTo(8f, 5f); lineTo(5f, 5f); lineTo(5f, 21f); lineTo(19f, 21f); lineTo(19f, 5f); lineTo(16f, 5f)
        moveTo(8f, 3f); lineTo(16f, 3f); lineTo(16f, 7f); lineTo(8f, 7f); close()
    }
    val Loop = outline("Loop") {
        moveTo(4f, 8f); lineTo(19f, 8f); lineTo(16f, 5f)
        moveTo(20f, 16f); lineTo(5f, 16f); lineTo(8f, 19f)
    }
    val Fade = outline("Fade") {
        moveTo(3f, 5f); lineTo(7f, 5f); lineTo(14f, 16f); lineTo(21f, 19f)
        moveTo(3f, 21f); lineTo(21f, 21f)
    }
    val Delete = outline("Delete") {
        moveTo(4f, 6f); lineTo(20f, 6f); moveTo(9f, 6f); lineTo(9f, 3f); lineTo(15f, 3f); lineTo(15f, 6f)
        moveTo(6f, 6f); lineTo(7f, 21f); lineTo(17f, 21f); lineTo(18f, 6f)
        moveTo(10f, 10f); lineTo(10f, 17f); moveTo(14f, 10f); lineTo(14f, 17f)
    }
    val Check = outline("Check") {
        moveTo(4f, 12f); lineTo(9f, 17f); lineTo(20f, 6f)
    }

    private fun outline(name: String, draw: PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
        }.build()

    private fun filled(name: String, draw: PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White), pathBuilder = draw)
        }.build()
}
