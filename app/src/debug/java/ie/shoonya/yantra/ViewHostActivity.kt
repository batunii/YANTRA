package ie.shoonya.yantra

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/**
 * An empty activity for view tests to put a view inside.
 *
 * Exists because touch dispatch needs a window. A view that has only been `measure`d and `layout`ed
 * by hand looks correct — right size, right children — and then drops every event on the floor,
 * because `ViewGroup` will not route to a child it has never really hosted. A gesture test built on
 * one passes its setup and asserts nothing, which is worse than no test at all.
 */
class ViewHostActivity : Activity() {
    lateinit var root: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        setContentView(root)
    }
}
