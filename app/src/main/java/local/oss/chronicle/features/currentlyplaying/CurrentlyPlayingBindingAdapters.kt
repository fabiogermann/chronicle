package local.oss.chronicle.features.currentlyplaying

import android.transition.TransitionManager
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.*
import androidx.databinding.BindingAdapter
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.transition.AutoTransition
import com.google.android.material.slider.Slider
import local.oss.chronicle.R
import local.oss.chronicle.application.MainActivityViewModel
import local.oss.chronicle.application.MainActivityViewModel.BottomSheetState.*
import timber.log.Timber

@BindingAdapter("bottomSheetState")
fun setBottomSheetState(
    parent: ConstraintLayout,
    state: MainActivityViewModel.BottomSheetState,
) {
    Timber.i("Bottom sheet state is $state")
    val constraints = ConstraintSet()
    constraints.clone(parent)
    when (state) {
        EXPANDED -> expandConstraint(constraints)
        COLLAPSED -> collapseConstraint(constraints)
        HIDDEN -> hideConstraint(constraints)
    }

    val transition = AutoTransition()
    transition.interpolator = FastOutSlowInInterpolator()
    transition.duration = parent.context.resources.getInteger(R.integer.short_animation_ms).toLong()
    TransitionManager.beginDelayedTransition(parent)
    parent.setConstraintSet(constraints)
    constraints.applyTo(parent)

    val bottomSheetHandle = parent.findViewById<View>(R.id.currently_playing_handle)
    bottomSheetHandle.visibility = if (state == COLLAPSED) View.VISIBLE else View.GONE
}

private fun collapseConstraint(constraintSet: ConstraintSet) {
    constraintSet.connect(
        R.id.currently_playing_container,
        TOP,
        R.id.currently_playing_collapsed_top,
        BOTTOM,
    )
    constraintSet.connect(R.id.currently_playing_container, BOTTOM, R.id.bottom_nav, TOP)
}

private fun expandConstraint(constraintSet: ConstraintSet) {
    constraintSet.connect(R.id.currently_playing_container, TOP, PARENT_ID, TOP)
    constraintSet.connect(R.id.currently_playing_container, BOTTOM, R.id.bottom_nav, TOP)
}

private fun hideConstraint(constraintSet: ConstraintSet) {
    constraintSet.connect(R.id.currently_playing_container, TOP, R.id.bottom_nav, TOP)
    constraintSet.connect(R.id.currently_playing_container, BOTTOM, R.id.bottom_nav, TOP)
}

/**
 * Binding adapter that safely sets the Slider's valueTo property.
 * Ensures valueTo is always greater than valueFrom to prevent IllegalStateException.
 *
 * When chapter data is not yet loaded, duration may be 0, which would cause
 * the Slider to crash with "valueFrom(0.0) must be smaller than valueTo(0.0)".
 * This adapter ensures a minimum value of 1 is used when the duration is 0 or negative.
 */
@BindingAdapter("safeValueTo")
fun setSafeValueTo(slider: Slider, valueTo: Int) {
    // Ensure valueTo is always greater than valueFrom (which defaults to 0)
    // Use 1 as the minimum to prevent the IllegalStateException
    val safeValueTo = if (valueTo <= slider.valueFrom) {
        1f
    } else {
        valueTo.toFloat()
    }

    // Only update if the value has changed to avoid unnecessary updates
    if (slider.valueTo != safeValueTo) {
        slider.valueTo = safeValueTo

        // Also ensure the current value doesn't exceed the new maximum
        if (slider.value > safeValueTo) {
            slider.value = safeValueTo
        }
    }
}
