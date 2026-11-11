package com.tayyab.blooddonation.ui

import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.DonationReminderScheduler
import com.tayyab.blooddonation.util.FcmPush
import com.tayyab.blooddonation.util.LowStock
import com.tayyab.blooddonation.util.RequestAlertScheduler
import com.tayyab.blooddonation.util.Session

/**
 * WhatsApp-style bottom navigation shared by the four main tabs (Home,
 * Donors, Stock, Requests), plus the floating "Can I donate?" chat button
 * and the three-dot overflow menu in the app bar.
 *
 * Navigation is activity-to-activity with FLAG_ACTIVITY_REORDER_TO_FRONT, so
 * the back stack never holds two copies of the same tab. Home is role-aware:
 * managers land on MainActivity, everyone else on UserHomeActivity.
 *
 * Badges are live local counts: Stock shows the groups at or below the low
 * threshold (see LowStock), Requests shows the pending requests. A badge
 * with nothing to show is removed, not left at zero.
 */
object BottomNav {

    /** Wire the bottom bar on a tab screen; [selectedId] is its own tab. */
    fun setup(activity: AppCompatActivity, selectedId: Int) {
        Session.load(activity)
        val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNav) ?: return
        nav.selectedItemId = selectedId
        nav.setOnItemSelectedListener { item ->
            if (item.itemId == selectedId) return@setOnItemSelectedListener true
            val target = when (item.itemId) {
                R.id.nav_home -> if (Session.isManager) MainActivity::class.java
                    else UserHomeActivity::class.java
                R.id.nav_donors -> DonorListActivity::class.java
                R.id.nav_stock -> StockActivity::class.java
                R.id.nav_requests -> RequestListActivity::class.java
                else -> return@setOnItemSelectedListener false
            }
            val intent = Intent(activity, target)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            activity.startActivity(intent)
            true
        }
        updateBadges(activity)
    }

    /** Refresh the Stock and Requests badges from the local database. Call
     *  from onResume and after any sync that changes the numbers. */
    fun updateBadges(activity: AppCompatActivity) {
        val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNav) ?: return
        val db = DbHelper(activity)
        Background.run {
            val stockBadge = LowStock.groups(db.stockLevels()).size
            val requestBadge = db.pendingRequestCount()
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    setBadge(nav, R.id.nav_stock, stockBadge)
                    setBadge(nav, R.id.nav_requests, requestBadge)
                }
            }
        }
    }

    private fun setBadge(nav: BottomNavigationView, itemId: Int, count: Int) {
        if (count <= 0) {
            nav.removeBadge(itemId)
            return
        }
        nav.getOrCreateBadge(itemId).number = count
    }

    /** The floating "Can I donate?" button: opens the AI helper chat. */
    fun setupChatFab(activity: AppCompatActivity) {
        activity.findViewById<View>(R.id.fabChat)?.setOnClickListener {
            activity.startActivity(Intent(activity, AskDonateActivity::class.java))
        }
    }

    /** Three-dot menu in the app bar (see R.menu.tab_overflow). The Activity
     *  forwards its three menu callbacks here. */
    fun onCreateOptionsMenu(activity: AppCompatActivity, menu: Menu): Boolean {
        activity.menuInflater.inflate(R.menu.tab_overflow, menu)
        return true
    }

    fun onPrepareOptionsMenu(activity: AppCompatActivity, menu: Menu) {
        Session.load(activity)
        val managerOnly = !Session.isManager
        menu.findItem(R.id.overflow_reports)?.isVisible = !managerOnly
        menu.findItem(R.id.overflow_moderation)?.isVisible = !managerOnly
    }

    fun onOptionsItemSelected(activity: AppCompatActivity, item: MenuItem): Boolean {
        val target = when (item.itemId) {
            R.id.overflow_camps -> CampListActivity::class.java
            R.id.overflow_slots -> SlotListActivity::class.java
            R.id.overflow_forecast -> ForecastActivity::class.java
            R.id.overflow_reports -> ManagerReportActivity::class.java
            R.id.overflow_moderation -> ModerationActivity::class.java
            R.id.overflow_emergency -> EmergencyActivity::class.java
            R.id.overflow_settings -> SettingsActivity::class.java
            R.id.overflow_logout -> {
                doLogout(activity)
                return true
            }
            else -> return false
        }
        activity.startActivity(Intent(activity, target))
        return true
    }

    /** Same sign-out as the home screens: stop the alert jobs, drop this
     *  device's push token, then clear the session and go to login. */
    private fun doLogout(activity: AppCompatActivity) {
        RequestAlertScheduler.cancel(activity)
        DonationReminderScheduler.cancel(activity)
        val appContext = activity.applicationContext
        Background.run { FcmPush.forgetToken(appContext) }
        Session.logout(activity)
        activity.startActivity(Intent(activity, LoginActivity::class.java))
        activity.finish()
    }
}
