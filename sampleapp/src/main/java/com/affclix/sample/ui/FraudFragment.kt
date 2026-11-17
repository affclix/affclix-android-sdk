package com.affclix.sample.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.affclix.sample.R
import com.affclix.sdk.AffclixSDK
import com.affclix.sdk.fraud.CtitCategory

class FraudFragment : Fragment() {

    private lateinit var logView:    TextView
    private lateinit var scrollView: ScrollView
    private lateinit var scoreBar:   ProgressBar
    private lateinit var scoreLabel: TextView
    private val log = StringBuilder()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_fraud, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        logView    = view.findViewById(R.id.tv_fraud_log)
        scrollView = view.findViewById(R.id.scroll_fraud)
        scoreBar   = view.findViewById(R.id.progress_fraud_score)
        scoreLabel = view.findViewById(R.id.tv_fraud_score_label)

        view.findViewById<Button>(R.id.btn_fraud_run).setOnClickListener         { runAssessment() }
        view.findViewById<Button>(R.id.btn_fraud_ctit).setOnClickListener        { showCtitTable() }
        view.findViewById<Button>(R.id.btn_fraud_fingerprint).setOnClickListener { showFingerprint() }
        view.findViewById<Button>(R.id.btn_fraud_payload).setOnClickListener     { showPayload() }
        view.findViewById<Button>(R.id.btn_fraud_clear).setOnClickListener       { clearLog() }

        runAssessment()
    }

    private fun runAssessment() {
        section("FRAUD ASSESSMENT")
        appendLog("Running...")
        Thread {
            val a = AffclixSDK.runFraudAssessment()
            activity?.runOnUiThread {
                scoreBar.progress = a.score
                scoreLabel.text   = "Fraud Score: ${a.score}/100 — ${riskLabel(a.score)}"
                scoreBar.progressTintList = ColorStateList.valueOf(
                    when {
                        a.score >= 70 -> 0xFFF44336.toInt()
                        a.score >= 40 -> 0xFFFF9800.toInt()
                        else          -> 0xFF4CAF50.toInt()
                    }
                )
                appendLog("")
                appendLog("fraud_score:  ${a.score}/100")
                appendLog("is_bot:       ${a.isBot}")
                appendLog("is_emulator:  ${a.isEmulator}  -> DB: ${if (a.isEmulator) 1 else 0}")
                appendLog("is_rooted:    ${a.isRooted}  -> DB: ${if (a.isRooted) 1 else 0}")
                appendLog("is_proxy:     ${a.isProxy}  -> DB: ${if (a.isProxy) 1 else 0}")
                appendLog("is_farm:      ${a.isFarm}  -> DB: ${if (a.isFarm) 1 else 0}")
                appendLog("reasons:      [${a.reasons.joinToString(", ")}]")
                appendLog("")
                if (a.signals.isEmpty()) {
                    appendLog("No fraud signals on this device")
                } else {
                    appendLog("${a.signals.size} signal(s):")
                    a.signals.forEach { s ->
                        val icon = if (s.weight >= 80) "HIGH" else if (s.weight >= 50) "MED" else "LOW"
                        appendLog("  [$icon] ${s.tag} w=${s.weight} ${s.detail}")
                    }
                }
            }
        }.start()
    }

    private fun showCtitTable() {
        section("CTIT TABLE (click-to-install time)")
        appendLog("Range         | Category       | Score")
        appendLog("< 0 sec       | NEGATIVE_CTIT  | 95  (clock skew)")
        appendLog("0-10 sec      | HIJACKING      | 95  (malware)")
        appendLog("10-60 sec     | DEVICE_FARM    | 80  (review)")
        appendLog("60-300 sec    | SUSPICIOUS     | 40  (flag)")
        appendLog("5min - 24hr   | NORMAL         | 0   (approve)")
        appendLog("1-7 days      | LONG           | 5   (approve)")
        appendLog("7-30 days     | VERY_LONG      | 15  (review)")
        appendLog("> 30 days     | CLICK_FLOODING | 70  (block)")
        appendLog("")
        appendLog("Live test:")
        val now = System.currentTimeMillis() / 1000
        listOf(5L, 40L, 200L, 600L, 86400L * 3, 86400L * 20, 86400L * 45).forEach { ctit ->
            val r = AffclixSDK.runCtitAssessment(now - ctit, now)
            appendLog("  ${ctit}s -> ${r.category} (score ${r.fraudScore})")
        }
    }

    private fun showFingerprint() {
        section("DEVICE FINGERPRINT")
        appendLog("Algorithm: SHA-256 of non-PII hardware signals")
        appendLog("")
        Thread {
            val d = AffclixSDK.getDeviceInfo()
            activity?.runOnUiThread {
                appendLog("device_type: ${d.deviceType}")
                appendLog("brand:       ${d.brand}")
                appendLog("model:       ${d.model}")
                appendLog("os_version:  ${d.osVersion}")
                appendLog("language:    ${d.language}")
                appendLog("timezone:    ${d.timezone}")
                appendLog("gaid:        ${d.gaid ?: "(unavailable)"}")
                appendLog("android_id:  ${d.androidId ?: "(unavailable)"}")
                appendLog("is_lat:      ${d.isLATEnabled}")
                appendLog("network:     ${d.networkType}")
                appendLog("connection:  ${d.connectionType}")
            }
        }.start()
    }

    private fun showPayload() {
        section("FRAUD PAYLOAD VALUES")
        Thread {
            val map = AffclixSDK.runFraudAssessment().toPayloadMap()
            activity?.runOnUiThread {
                map.forEach { (k, v) -> appendLog("$k = $v") }
            }
        }.start()
    }

    private fun riskLabel(score: Int) = when {
        score >= 70 -> "BLOCKED"
        score >= 40 -> "SUSPICIOUS"
        score >= 15 -> "LOW RISK"
        else        -> "CLEAN"
    }

    private fun section(title: String) = appendLog("\n-- $title --")

    private fun appendLog(line: String) {
        activity?.runOnUiThread {
            log.appendLine(line)
            logView.text = log.toString()
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun clearLog() {
        log.clear()
        scoreBar.progress = 0
        scoreLabel.text   = "Fraud Score: --"
        logView.text      = "Log cleared.\n"
    }
}