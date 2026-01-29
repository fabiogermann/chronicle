package local.oss.chronicle.features.library

import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.databinding.BindingAdapter
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import local.oss.chronicle.R
import local.oss.chronicle.application.Injector
import local.oss.chronicle.data.model.Audiobook
import kotlin.math.abs

/**
 * Global ConstraintSet for aspect ratio changes to avoid repeated allocations.
 */
private val GLOBAL_CONSTRAINT = ConstraintSet()

/**
 * Color palette for library badges.
 * These colors are used to visually distinguish libraries from different accounts.
 */
private val LIBRARY_BADGE_COLORS = listOf(
    0xFF1976D2.toInt(), // Blue
    0xFF388E3C.toInt(), // Green
    0xFFF57C00.toInt(), // Orange
    0xFF7B1FA2.toInt(), // Purple
    0xFFD32F2F.toInt(), // Red
    0xFF00796B.toInt(), // Teal
)

/**
 * Cache of library ID to library name mappings for efficient lookups.
 * Updated whenever libraries are loaded/refreshed.
 */
private var libraryNamesCache: Map<String, String> = emptyMap()

/**
 * Updates the library names cache. Should be called when libraries change.
 */
fun updateLibraryNamesCache(libraries: Map<String, String>) {
    libraryNamesCache = libraries
}

/**
 * Clears the library names cache.
 */
fun clearLibraryNamesCache() {
    libraryNamesCache = emptyMap()
}

/**
 * Binding adapter that displays a library name from a library ID with a colored background.
 * Shows the library name if found in cache, otherwise attempts to load it.
 * Background color is determined by hashing the library ID for consistent colors per library.
 *
 * @param view The TextView to display the library name
 * @param libraryId The library ID (format: "plex:library:{id}")
 */
@BindingAdapter("libraryIdToName")
fun setLibraryNameFromId(
    view: TextView,
    libraryId: String?,
) {
    if (libraryId == null) {
        view.visibility = View.GONE
        return
    }

    // Set background color based on libraryId hash
    val colorIndex = abs(libraryId.hashCode()) % LIBRARY_BADGE_COLORS.size
    val color = LIBRARY_BADGE_COLORS[colorIndex]

    Log.d("LibraryBadge", "LibraryID: $libraryId, ColorIndex: $colorIndex, Color: ${String.format("#%08X", color)}")

    // Create a rounded background drawable with the color
    val drawable = GradientDrawable()
    drawable.setColor(color)
    drawable.cornerRadius = view.resources.displayMetrics.density * 4f // 4dp rounded corners
    drawable.alpha = 204 // ~80% opacity (204/255)

    view.background = drawable

    // Set padding (convert dp to pixels)
    val density = view.resources.displayMetrics.density
    val paddingHorizontal = (8 * density).toInt() // 8dp
    val paddingVertical = (4 * density).toInt() // 4dp
    view.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)

    // Check cache first
    val cachedName = libraryNamesCache[libraryId]
    if (cachedName != null) {
        view.text = cachedName
        view.visibility = View.VISIBLE
        Log.d("LibraryBadge", "  -> LibraryName (cached): $cachedName")
        return
    }

    // If not in cache, try to load it asynchronously
    val lifecycleOwner = view.findViewTreeLifecycleOwner()
    if (lifecycleOwner != null) {
        lifecycleOwner.lifecycleScope.launch {
            try {
                val libraryRepository = Injector.get().libraryRepository()
                val library = libraryRepository.getLibraryById(libraryId)
                if (library != null) {
                    view.text = library.name
                    view.visibility = View.VISIBLE
                    Log.d("LibraryBadge", "  -> LibraryName (loaded): ${library.name}")
                    // Update cache for future uses
                    libraryNamesCache = libraryNamesCache + (libraryId to library.name)
                } else {
                    // Library not found, hide the view
                    view.visibility = View.GONE
                }
            } catch (e: Exception) {
                // Error loading library, hide the view
                view.visibility = View.GONE
            }
        }
    } else {
        // No lifecycle owner available, hide the view
        view.visibility = View.GONE
    }
}

/**
 * Binding adapter that sets a colored bar based on library ID.
 * Used as a bottom bar indicator on audiobook covers.
 * Background color is determined by hashing the library ID for consistent colors per library.
 *
 * @param view The View to color (typically a bottom bar indicator)
 * @param libraryId The library ID (format: "plex:library:{id}")
 */
@BindingAdapter("libraryBarColor")
fun setLibraryBarColor(
    view: View,
    libraryId: String?,
) {
    if (libraryId == null) {
        view.visibility = View.GONE
        return
    }

    // Set background color based on libraryId hash
    val colorIndex = abs(libraryId.hashCode()) % LIBRARY_BADGE_COLORS.size
    val color = LIBRARY_BADGE_COLORS[colorIndex]

    Log.d("LibraryBadge", "LibraryBar - LibraryID: $libraryId, ColorIndex: $colorIndex, Color: ${String.format("#%08X", color)}")

    view.setBackgroundColor(color)
    view.visibility = View.VISIBLE
}

/**
 * Binding adapter to set a view to square aspect ratio.
 * Used in grid layouts to ensure consistent square or rectangular aspect ratios.
 */
@BindingAdapter("isSquare")
fun setSquareAspectRatio(constraintLayout: ConstraintLayout, isSquare: Boolean) {
    GLOBAL_CONSTRAINT.clone(constraintLayout)
    if (isSquare) {
        GLOBAL_CONSTRAINT.setDimensionRatio(R.id.thumb_container, "1:1")
    } else {
        GLOBAL_CONSTRAINT.setDimensionRatio(R.id.thumb_container, "5:8")
    }
    constraintLayout.setConstraintSet(GLOBAL_CONSTRAINT)
}

/**
 * Binding adapter to override the width of a view.
 * Used in grid layouts to dynamically adjust item widths.
 * Accepts dimension resources which are float values.
 */
@BindingAdapter("overrideWidth")
fun overrideWidth(view: View, width: Float) {
    view.layoutParams.width = if (width > 0) width.toInt() else MATCH_PARENT
}

/**
 * Binding adapter to bind a list of audiobooks to a RecyclerView.
 * Used for displaying audiobook lists in various views.
 */
@BindingAdapter("bookList")
fun bindRecyclerView(recyclerView: RecyclerView, data: List<Audiobook>?) {
    val adapter = recyclerView.adapter as AudiobookAdapter
    adapter.submitList(data)
}

/**
 * Binding adapter to set server connection status on the audiobook adapter.
 * Used to update UI based on server connectivity.
 */
@BindingAdapter("serverConnected")
fun bindRecyclerView(recyclerView: RecyclerView, serverConnected: Boolean) {
    val adapter = recyclerView.adapter as AudiobookAdapter
    adapter.setServerConnected(serverConnected)
}
