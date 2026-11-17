package com.affclix.sample.ui

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.affclix.sample.R
import com.affclix.sdk.AffclixSDK

class DeviceInfoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_info)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Device Info"

        val tv = findViewById<TextView>(R.id.tv_device_info)
        tv.text = "Loading..."

        Thread {
            val d  = AffclixSDK.getDeviceInfo()
            val a  = AffclixSDK.getAttribution()
            val sb = StringBuilder()

            sb.appendLine("-- APP --")
            sb.appendLine("app_id:       ${d.appPackage}")
            sb.appendLine("app_version:  ${d.appVersion}")
            sb.appendLine("sdk_version:  ${AffclixSDK.getVersion()}")
            sb.appendLine("")
            sb.appendLine("-- VISITOR --")
            sb.appendLine("visitor_id:   ${AffclixSDK.getVisitorId()}")
            sb.appendLine("session_id:   ${AffclixSDK.getSessionId()}")
            sb.appendLine("click_id:     ${AffclixSDK.getClickId() ?: "(none)"}")
            sb.appendLine("")
            sb.appendLine("-- MOBILE IDs --")
            sb.appendLine("gaid:         ${d.gaid ?: "(unavailable)"}")
            sb.appendLine("android_id:   ${d.androidId ?: "(unavailable)"}")
            sb.appendLine("is_lat:       ${d.isLATEnabled}")
            sb.appendLine("")
            sb.appendLine("-- DEVICE --")
            sb.appendLine("device_type:  ${d.deviceType}")
            sb.appendLine("brand:        ${d.brand}")
            sb.appendLine("model:        ${d.model}")
            sb.appendLine("os_version:   ${d.osVersion}")
            sb.appendLine("screen:       ${d.screenWidth}x${d.screenHeight}")
            sb.appendLine("dpi:          ${d.dpi}")
            sb.appendLine("")
            sb.appendLine("-- LOCALE --")
            sb.appendLine("language:     ${d.language}")
            sb.appendLine("timezone:     ${d.timezone}")
            sb.appendLine("")
            sb.appendLine("-- NETWORK --")
            sb.appendLine("carrier:      ${d.carrier ?: "(none)"}")
            sb.appendLine("network_type: ${d.networkType}")
            sb.appendLine("connection:   ${d.connectionType}")
            sb.appendLine("")
            sb.appendLine("-- ATTRIBUTION --")
            sb.appendLine("media_source: ${a.mediaSource ?: "(organic)"}")
            sb.appendLine("utm_source:   ${a.utmSource ?: "(none)"}")
            sb.appendLine("utm_medium:   ${a.utmMedium ?: "(none)"}")
            sb.appendLine("utm_campaign: ${a.utmCampaign ?: "(none)"}")
            sb.appendLine("")
            sb.appendLine("-- INSTALL --")
            val installTime = AffclixSDK.getInstallTime()
            sb.appendLine("install_time: ${installTime ?: "(not yet recorded)"}")

            runOnUiThread { tv.text = sb.toString() }
        }.start()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

// Stub activities declared in AndroidManifest
class RequestInspectorActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) { super.onCreate(s); finish() }
}

class FraudReportActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) { super.onCreate(s); finish() }
}