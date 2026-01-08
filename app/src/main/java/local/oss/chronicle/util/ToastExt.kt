package local.oss.chronicle.util

import android.content.Context
import android.widget.Toast.makeText
import local.oss.chronicle.views.BottomSheetChooser.FormattableString

fun showToast(
    context: Context,
    formattableString: FormattableString,
    duration: Int,
) {
    makeText(context, formattableString.format(context.resources), duration).show()
}
