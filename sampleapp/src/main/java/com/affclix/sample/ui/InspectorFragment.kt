package com.affclix.sample.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.affclix.sample.R
import com.affclix.sample.RequestEntry
import com.affclix.sample.SampleApp

class InspectorFragment : Fragment() {

    private lateinit var recycler:   RecyclerView
    private lateinit var emptyView:  TextView
    private lateinit var countLabel: TextView
    private lateinit var adapter:    RequestAdapter

    private val listener: () -> Unit = {
        activity?.runOnUiThread { refresh() }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_inspector, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler    = view.findViewById(R.id.rv_requests)
        emptyView   = view.findViewById(R.id.tv_inspector_empty)
        countLabel  = view.findViewById(R.id.tv_request_count)

        adapter = RequestAdapter()
        recycler.adapter       = adapter
        recycler.layoutManager = LinearLayoutManager(requireContext())

        view.findViewById<Button>(R.id.btn_inspector_clear).setOnClickListener {
            synchronized(SampleApp.requestLog) { SampleApp.requestLog.clear() }
            refresh()
        }

        SampleApp.requestListeners.add(listener)
        refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        SampleApp.requestListeners.remove(listener)
    }

    private fun refresh() {
        val list = synchronized(SampleApp.requestLog) { SampleApp.requestLog.toList() }
        adapter.update(list)
        countLabel.text      = "${list.size} requests"
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility  = if (list.isEmpty()) View.GONE    else View.VISIBLE
    }

    inner class RequestAdapter : RecyclerView.Adapter<RequestViewHolder>() {
        private var items    = listOf<RequestEntry>()
        private val expanded = mutableSetOf<Int>()

        fun update(newItems: List<RequestEntry>) {
            items = newItems
            expanded.clear()
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RequestViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_request, parent, false)
            return RequestViewHolder(v)
        }

        override fun onBindViewHolder(holder: RequestViewHolder, position: Int) {
            val isExpanded = expanded.contains(position)
            holder.bind(items[position], isExpanded)
            holder.itemView.setOnClickListener {
                if (isExpanded) expanded.remove(position) else expanded.add(position)
                notifyItemChanged(position)
            }
        }
    }

    inner class RequestViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvEndpoint: TextView = view.findViewById(R.id.tv_req_endpoint)
        private val tvTime:     TextView = view.findViewById(R.id.tv_req_time)
        private val tvParams:   TextView = view.findViewById(R.id.tv_req_params)
        private val indicator:  View     = view.findViewById(R.id.view_endpoint_color)

        fun bind(entry: RequestEntry, expanded: Boolean) {
            tvEndpoint.text = "${entry.endpoint} ${entry.method}"
            tvTime.text     = entry.formattedTime
            indicator.setBackgroundColor(entry.statusColor)

            if (expanded && entry.params.isNotEmpty()) {
                val sb = StringBuilder()
                val groups = linkedMapOf(
                    "IDENTITY"     to listOf("vid","sid","platform","app_id","sdk_version","ep"),
                    "CAMPAIGN"     to listOf("d","c","a","cr","ad_type","iid","vta","source","redirect_ms"),
                    "DEVICE"       to listOf("device_type","device_brand","device_name","os","os_version","gaid","android_id","is_lat","lang","tz","fp"),
                    "NETWORK"      to listOf("wifi","connection_type"),
                    "ATTRIBUTION"  to listOf("media_source","utm_source","utm_medium","utm_campaign","utm_term","utm_content","gbraid","wbraid"),
                    "FRAUD"        to listOf("fraud_score","is_proxy","is_emulator","is_rooted","is_farm","fraud_reasons","ctit_seconds","ctit_category"),
                    "RETARGET"     to listOf("is_retargeting","retargeting_conversion_type"),
                    "CONTRIBUTORS" to listOf("contributor_1_media_source","contributor_1_campaign","contributor_1_touch_type","contributor_2_media_source","contributor_3_media_source"),
                    "REVENUE"      to listOf("sale","currency","order_id","product_id","txid","event","time_to_convert"),
                    "SUBS"         to listOf("sub1","sub2","sub3","sub4","sub5","sub6","sub_adv_id1","sub_adv_id2","sub_adv_id3")
                )
                val shown = mutableSetOf<String>()
                groups.forEach { (name, keys) ->
                    val present = keys.filter { it in entry.params && it !in shown }
                    if (present.isNotEmpty()) {
                        sb.appendLine("-- $name --")
                        present.forEach { k ->
                            val pad = k.padEnd(28)
                            sb.appendLine("  $pad = ${entry.params[k]}")
                            shown.add(k)
                        }
                    }
                }
                val remaining = entry.params.keys.filter { it !in shown }
                if (remaining.isNotEmpty()) {
                    sb.appendLine("-- OTHER --")
                    remaining.forEach { k ->
                        val pad = k.padEnd(28)
                        sb.appendLine("  $pad = ${entry.params[k]}")
                    }
                }
                sb.appendLine("-- RESPONSE --")
                sb.appendLine("  ${entry.response}")
                tvParams.text       = sb.toString()
                tvParams.visibility = View.VISIBLE
            } else {
                val preview = listOf("c","a","event","txid","iid","click_id")
                    .mapNotNull { k -> entry.params[k]?.let { "$k=$it" } }
                    .take(3).joinToString("  ")
                tvParams.text       = if (preview.isNotBlank()) "  $preview" else "  (tap to expand)"
                tvParams.visibility = View.VISIBLE
            }
        }
    }
}