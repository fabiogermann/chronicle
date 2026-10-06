@file:Suppress("DEPRECATION")

package local.oss.chronicle.views

import android.content.Context
import android.util.AttributeSet
import com.facebook.drawee.generic.GenericDraweeHierarchy
import com.facebook.drawee.generic.GenericDraweeHierarchyInflater
import com.facebook.drawee.view.DraweeView
import com.facebook.imagepipeline.systrace.FrescoSystrace

/**
 * Thin replacement for Fresco's deprecated [com.facebook.drawee.view.GenericDraweeView].
 * It inflates a [GenericDraweeHierarchy] from XML attributes without extending the deprecated class.
 */
class ChronicleDraweeView : DraweeView<GenericDraweeHierarchy> {
    constructor(context: Context, hierarchy: GenericDraweeHierarchy) : super(context) {
        setHierarchy(hierarchy)
    }

    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
        defStyleRes: Int = 0,
    ) : super(context, attrs ?: EmptyAttributeSet, defStyleAttr, defStyleRes) {
        inflateHierarchy(context, attrs)
    }

    private fun inflateHierarchy(
        context: Context,
        attrs: AttributeSet?,
    ) {
        val traceTag = "ChronicleDraweeView#inflateHierarchy"
        val tracing = FrescoSystrace.isTracing()
        if (tracing) {
            FrescoSystrace.beginSection(traceTag)
        }
        val builder = GenericDraweeHierarchyInflater.inflateBuilder(context, attrs)
        aspectRatio = builder.desiredAspectRatio
        hierarchy = builder.build()
        if (tracing) {
            FrescoSystrace.endSection()
        }
    }

    /** No-op [AttributeSet] — Fresco 3.9's [DraweeView] constructor takes a non-null attrs. */
    private object EmptyAttributeSet : AttributeSet {
        override fun getAttributeCount(): Int = 0

        override fun getAttributeName(index: Int): String = throw IndexOutOfBoundsException()

        override fun getAttributeValue(index: Int): String? = null

        override fun getAttributeValue(
            namespace: String?,
            name: String?,
        ): String? = null

        override fun getPositionDescription(): String = ""

        override fun getAttributeNameResource(index: Int): Int = 0

        override fun getAttributeListValue(
            namespace: String?,
            attribute: String?,
            options: Array<String>?,
            defaultValue: Int,
        ): Int = defaultValue

        override fun getAttributeListValue(
            index: Int,
            options: Array<String>?,
            defaultValue: Int,
        ): Int = defaultValue

        override fun getAttributeBooleanValue(
            namespace: String?,
            attribute: String?,
            defaultValue: Boolean,
        ): Boolean = defaultValue

        override fun getAttributeBooleanValue(
            index: Int,
            defaultValue: Boolean,
        ): Boolean = defaultValue

        override fun getAttributeResourceValue(
            namespace: String?,
            attribute: String?,
            defaultValue: Int,
        ): Int = defaultValue

        override fun getAttributeResourceValue(
            index: Int,
            defaultValue: Int,
        ): Int = defaultValue

        override fun getAttributeIntValue(
            namespace: String?,
            attribute: String?,
            defaultValue: Int,
        ): Int = defaultValue

        override fun getAttributeIntValue(
            index: Int,
            defaultValue: Int,
        ): Int = defaultValue

        override fun getAttributeUnsignedIntValue(
            namespace: String?,
            attribute: String?,
            defaultValue: Int,
        ): Int = defaultValue

        override fun getAttributeUnsignedIntValue(
            index: Int,
            defaultValue: Int,
        ): Int = defaultValue

        override fun getAttributeFloatValue(
            namespace: String?,
            attribute: String?,
            defaultValue: Float,
        ): Float = defaultValue

        override fun getAttributeFloatValue(
            index: Int,
            defaultValue: Float,
        ): Float = defaultValue

        override fun getIdAttribute(): String? = null

        override fun getClassAttribute(): String? = null

        override fun getIdAttributeResourceValue(defaultValue: Int): Int = defaultValue

        override fun getStyleAttribute(): Int = 0
    }
}
