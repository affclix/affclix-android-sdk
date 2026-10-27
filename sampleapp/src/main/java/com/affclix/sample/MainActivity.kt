package com.affclix.sample

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.affclix.sample.ui.AttributionFragment
import com.affclix.sample.ui.DeviceInfoActivity
import com.affclix.sample.ui.FraudFragment
import com.affclix.sample.ui.InspectorFragment
import com.affclix.sample.ui.TrackFragment
import com.affclix.sdk.AffclixSDK
import com.affclix.sdk.internal.DeepLinkCallback
import com.affclix.sdk.model.DeepLinkResult
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout

    private val tabTitles = listOf("Track", "Attribution", "Fraud", "Inspector")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))

        viewPager = findViewById(R.id.view_pager)
        tabLayout = findViewById(R.id.tab_layout)

        viewPager.adapter = DashboardPagerAdapter(this)
        viewPager.offscreenPageLimit = 3

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()

        handleDeepLink(intent)

        supportActionBar?.subtitle = "visitor: ${AffclixSDK.getVisitorId().take(12)}..."
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        AffclixSDK.handleDeepLink(intent, object : DeepLinkCallback {
            override fun onDeepLinkResolved(result: DeepLinkResult) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Deep link!\nclick=${result.clickId}\nsource=${result.utmSource}",
                        Toast.LENGTH_LONG
                    ).show()
                    viewPager.currentItem = 1
                }
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_device_info -> {
                startActivity(Intent(this, DeviceInfoActivity::class.java))
                true
            }
            R.id.action_fire_deep_link -> {
                val uri = Uri.parse(
                    "affclix://open?click_id=test_dl_001" +
                    "&campaign_id=camp_test&affiliate_id=aff_001" +
                    "&utm_source=push_notification&utm_medium=push" +
                    "&utm_campaign=reactivation"
                )
                val deepIntent = Intent(Intent.ACTION_VIEW, uri)
                deepIntent.setPackage(packageName)
                startActivity(deepIntent)
                true
            }
            R.id.action_clear_data -> {
                AffclixSDK.revokeConsent()
                synchronized(SampleApp.requestLog) { SampleApp.requestLog.clear() }
                SampleApp.notifyRequestReceived()
                Toast.makeText(this, "All SDK data cleared", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    inner class DashboardPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount() = 4
        override fun createFragment(position: Int): Fragment = when (position) {
            0    -> TrackFragment()
            1    -> AttributionFragment()
            2    -> FraudFragment()
            3    -> InspectorFragment()
            else -> TrackFragment()
        }
    }
}