package local.oss.chronicle.features.account

import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.View
import androidx.databinding.BindingAdapter
import local.oss.chronicle.data.model.Library
import kotlin.math.abs

/**
 * Color palette for library indicators.
 * These colors are used to visually distinguish libraries.
 */
private val LIBRARY_COLORS = listOf(
    0xFF1976D2.toInt(), // Blue
    0xFF388E3C.toInt(), // Green
    0xFFF57C00.toInt(), // Orange
    0xFF7B1FA2.toInt(), // Purple
    0xFFD32F2F.toInt(), // Red
    0xFF00796B.toInt(), // Teal
)

/**
 * Binding adapter that sets the background color of a library indicator based on the library ID.
 * Uses a consistent color based on the hash of the library ID.
 *
 * @param view The View to set the background color on
 * @param library The Library object
 */
@BindingAdapter("libraryColor")
fun setLibraryColor(view: View, library: Library?) {
    if (library == null) {
        return
    }

    // Set background color based on library ID hash
    val colorIndex = abs(library.id.hashCode()) % LIBRARY_COLORS.size
    val color = LIBRARY_COLORS[colorIndex]

    Log.d("LibraryCircle", "LibraryID: ${library.id}, LibraryName: ${library.name}, ColorIndex: $colorIndex, Color: ${String.format("#%08X", color)}")

    // Create a circular background drawable with the color
    val drawable = GradientDrawable()
    drawable.shape = GradientDrawable.OVAL
    drawable.setColor(color)
    drawable.alpha = 204 // ~80% opacity to match library badges

    view.background = drawable
}
