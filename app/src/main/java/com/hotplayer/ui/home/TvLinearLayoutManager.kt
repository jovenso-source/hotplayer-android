package com.hotplayer.ui.home

import android.content.Context
import android.util.Log
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Keeps D-pad focus strictly inside the RecyclerView at all times.
 *
 * Three complementary mechanisms:
 *  - findItemView           : walks up the view hierarchy to find the direct RecyclerView
 *                             child. Necessary because `focused` can be a grandchild
 *                             (e.g. a ProgressBar inside an item). Calling getPosition()
 *                             on a grandchild throws ClassCastException (its LayoutParams
 *                             are LinearLayout.LayoutParams, not RecyclerView.LayoutParams)
 *                             which crashes the Activity and returns to home.
 *  - onInterceptFocusSearch : traps UP on item 0 and DOWN on last item (edge guard).
 *  - onFocusSearchFailed    : fires when LinearLayoutManager can't find the next item
 *                             during fast scroll (item not yet bound). Returning null
 *                             triggers Android's full-screen spatial search which escapes
 *                             to adjacent columns. We return `focused` instead.
 *
 * All overrides are wrapped in try/catch to guarantee the Activity never crashes from
 * layout-manager callbacks (which can fire during RecyclerView transitions).
 */
class TvLinearLayoutManager(context: Context) : LinearLayoutManager(context) {

    companion object {
        private const val TAG = "TvLinearLM"
    }

    // Walks up from `view` until the parent is a RecyclerView — that view is the direct item child.
    // Returns null if `view` is not a descendant of any RecyclerView (e.g. if `view` IS
    // the RecyclerView itself, which can happen when the list is empty and the RV has focus).
    private fun findItemView(view: View): View? {
        var current: View = view
        var safety = 0
        while (safety++ < 20) {
            val parent = current.parent ?: return null
            if (parent is RecyclerView) return current
            current = parent as? View ?: return null
        }
        return null
    }

    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        return try {
            if (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) {
                val itemView = findItemView(focused)
                    ?: return super.onInterceptFocusSearch(focused, direction)

                val pos = try {
                    getPosition(itemView)
                } catch (e: ClassCastException) {
                    // itemView's LayoutParams are not RecyclerView.LayoutParams — this can
                    // happen transiently during submitList diff application. Fall through
                    // to super rather than crashing.
                    Log.w(TAG, "getPosition ClassCastException — falling back to super")
                    return super.onInterceptFocusSearch(focused, direction)
                }

                if (pos == RecyclerView.NO_POSITION)
                    return super.onInterceptFocusSearch(focused, direction)

                // Edge guard: block D-pad from escaping the list at top/bottom boundaries.
                if (direction == View.FOCUS_UP   && pos == 0)             return focused
                if (direction == View.FOCUS_DOWN && pos == itemCount - 1) return focused
            }
            super.onInterceptFocusSearch(focused, direction)
        } catch (e: Exception) {
            Log.w(TAG, "onInterceptFocusSearch error: ${e.message}")
            focused
        }
    }

    override fun onFocusSearchFailed(
        focused: View,
        focusDirection: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): View? {
        return try {
            // Call super for its scroll side-effect (reveals the next item), but never return its
            // result: during key-repeat the returned view is mid-layout and loses focus immediately,
            // triggering Android's global spatial search which escapes to the category column.
            super.onFocusSearchFailed(focused, focusDirection, recycler, state)
            focused
        } catch (e: Exception) {
            Log.w(TAG, "onFocusSearchFailed error: ${e.message}")
            focused
        }
    }
}
