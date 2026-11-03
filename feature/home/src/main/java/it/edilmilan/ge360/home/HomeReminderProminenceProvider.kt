package it.edilmilan.ge360.home

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import java.util.WeakHashMap

/**
 * Rende il blocco Promemoria prioritario nella Home.
 * La chat Jarvis non viene più iniettata qui: è presente direttamente nel layout
 * della Home, così non dipende dal timing del lifecycle dell'Activity.
 */
class HomeReminderProminenceProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {

    private val observed = WeakHashMap<TodayDashboardView, Boolean>()

    override fun onCreate(): Boolean {
        val application = context?.applicationContext as? Application ?: return false
        application.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val dashboard = findDashboard(content) ?: return
        attach(dashboard)
        dashboard.post { promoteReminder(dashboard) }
    }

    private fun attach(dashboard: TodayDashboardView) {
        if (observed.containsKey(dashboard)) return
        observed[dashboard] = true
        dashboard.viewTreeObserver.addOnGlobalLayoutListener {
            dashboard.post { promoteReminder(dashboard) }
        }
    }

    private fun promoteReminder(dashboard: TodayDashboardView) {
        if (dashboard.childCount < 2) return
        val reminder = (0 until dashboard.childCount)
            .map { dashboard.getChildAt(it) }
            .firstOrNull { containsText(it, "PROMEMORIA") } as? MaterialCardView
            ?: return

        val index = dashboard.indexOfChild(reminder)
        if (index != 1) {
            val params = reminder.layoutParams
            dashboard.removeView(reminder)
            dashboard.addView(reminder, 1, params)
        }

        val lp = reminder.layoutParams as? ViewGroup.MarginLayoutParams
        lp?.apply {
            topMargin = 0
            bottomMargin = dp(dashboard, 12)
            reminder.layoutParams = this
        }

        reminder.cardElevation = dp(dashboard, 2).toFloat()
        reminder.strokeWidth = dp(dashboard, 2)
        reminder.strokeColor = Ge360Brand.PrimaryDark
        reminder.setCardBackgroundColor(Ge360Brand.SurfaceWarm)

        walk(reminder) { view ->
            when (view) {
                is MaterialButton -> if (view.text.toString().contains("NOTA")) {
                    view.textSize = 12f
                    view.minHeight = dp(dashboard, 50)
                    view.setTextColor(Ge360Brand.OnPrimary)
                }
                is TextView -> {
                    when {
                        view.text.toString() == "PROMEMORIA" -> {
                            view.textSize = 13f
                            view.setTextColor(Ge360Brand.PrimaryDark)
                            view.setTypeface(view.typeface, android.graphics.Typeface.BOLD)
                        }
                        view.text.toString().contains("cosa da fare") || view.text.toString().contains("cose da fare") -> {
                            view.textSize = 21f
                            view.setTextColor(Ge360Brand.ForestDark)
                            view.setTypeface(view.typeface, android.graphics.Typeface.BOLD)
                        }
                    }
                }
            }
        }
    }

    private fun containsText(view: View, target: String): Boolean {
        if (view is TextView && view.text.toString() == target) return true
        if (view !is ViewGroup) return false
        for (i in 0 until view.childCount) {
            if (containsText(view.getChildAt(i), target)) return true
        }
        return false
    }

    private fun walk(view: View, block: (View) -> Unit) {
        block(view)
        if (view !is ViewGroup) return
        for (i in 0 until view.childCount) walk(view.getChildAt(i), block)
    }

    private fun findDashboard(view: View): TodayDashboardView? {
        if (view is TodayDashboardView) return view
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) {
            findDashboard(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).toInt()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
