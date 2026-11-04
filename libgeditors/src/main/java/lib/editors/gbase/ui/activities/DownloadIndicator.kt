package lib.editors.gbase.ui.activities

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.progressindicator.CircularProgressIndicator
import lib.editors.resources.R as Resources

/**
 * Downloads over the top of the document (in [parent]): a circle that fills up as the last one
 * started comes, beside its name. [start] one, its [progress], [end] it.
 */
internal class DownloadIndicator(private val parent: FrameLayout) {

    // By key, in the order they started; progress null while the size isn't known
    private class Download(val label: String, val description: String, var progress: Float?)

    private val downloads = linkedMapOf<String, Download>()
    private val context = parent.context
    private val density = context.resources.displayMetrics.density
    private var shownKey: String? = null

    private val circle = CircularProgressIndicator(context).apply {
        indicatorSize = dp(20)
        trackThickness = dp(3)
        setIndicatorColor(ContextCompat.getColor(context, Resources.color.editors_accent))
        isIndeterminate = true
    }
    private val label = TextView(context).apply {
        textSize = 14f
        maxLines = 1
        setTextColor(ContextCompat.getColor(context, Resources.color.editors_icon))
    }
    private val view: View by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(12), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(ContextCompat.getColor(context, Resources.color.editors_bar))
            }
            elevation = dp(4).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            // Read out when its description changes (see update)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(circle)
            addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(10)
            })
            isVisible = false
        }.also {
            parent.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
            })
        }
    }

    /** [label] shows, [description] is read out (screen readers); [progress] if some came already. */
    fun start(key: String, label: String, description: String, progress: Float? = null) {
        downloads.remove(key)
        downloads[key] = Download(label, description, progress)
        update()
    }

    /** How much of it came, 0 to 1. */
    fun progress(key: String, fraction: Float) {
        val download = downloads[key] ?: return
        download.progress = fraction
        if (key == shownKey) circle.setProgressCompat((fraction * 100).toInt(), true)
    }

    fun end(key: String) {
        if (downloads.remove(key) != null) update()
    }

    private fun update() {
        val (key, download) = downloads.entries.lastOrNull()?.toPair() ?: run {
            shownKey = null
            view.isVisible = false
            // So that the next one is read out, the same download too
            view.contentDescription = null
            return
        }
        if (key == shownKey) return
        shownKey = key
        label.text = download.label
        // Going round until its size is known, then filling up (setProgressCompat switches)
        val progress = download.progress
        if (progress == null) circle.isIndeterminate = true else circle.setProgressCompat((progress * 100).toInt(), false)
        view.isVisible = true
        // Once it shows: a live region that appears isn't always read out, a change in it is
        view.contentDescription = download.description
    }

    private fun dp(value: Int) = (value * density).toInt()
}
