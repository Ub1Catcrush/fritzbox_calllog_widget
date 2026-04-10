package com.tvcs.fritzboxcallwidget.prefs

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvcs.fritzboxcallwidget.R
import com.tvcs.fritzboxcallwidget.api.ConnectivityChecker
import com.tvcs.fritzboxcallwidget.api.ConnectivityChecker.CheckStep
import com.tvcs.fritzboxcallwidget.api.ConnectionProfile
import com.tvcs.fritzboxcallwidget.api.ConnectionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

class ConnectionProfilesActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ProfileAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private lateinit var prefs: AppPreferences

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(SettingsActivity.wrapLocale(base, AppPreferences(base).language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connection_profiles)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.pref_connections_title)
        }
        prefs = AppPreferences(this)

        recyclerView = findViewById(R.id.rv_profiles)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = ProfileAdapter(
            profiles    = prefs.getOrderedProfiles().toMutableList(),
            onMove      = { from, to -> swapProfiles(from, to) },
            onToggle    = { index, enabled -> toggleProfile(index, enabled) },
            onEdit      = { index -> editProfile(index) },
            onTest      = { index -> testProfile(index) },
            onDragStart = { vh -> itemTouchHelper.startDrag(vh) }
        )
        recyclerView.adapter = adapter

        itemTouchHelper = ItemTouchHelper(ProfileDragCallback(adapter))
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onPause() {
        super.onPause()
        prefs.saveOrderedProfiles(adapter.profiles)
    }

    // ── Profile operations ────────────────────────────────────────────────────

    private fun swapProfiles(from: Int, to: Int) {
        Collections.swap(adapter.profiles, from, to)
        adapter.notifyItemMoved(from, to)
        prefs.saveOrderedProfiles(adapter.profiles)
    }

    private fun toggleProfile(index: Int, enabled: Boolean) {
        if (!enabled && adapter.profiles.count { it.enabled } <= 1) {
            Toast.makeText(this, R.string.profile_min_one_required, Toast.LENGTH_SHORT).show()
            adapter.notifyItemChanged(index)
            return
        }
        adapter.profiles[index] = adapter.profiles[index].copy(enabled = enabled)
        prefs.saveOrderedProfiles(adapter.profiles)
    }

    private fun editProfile(index: Int) {
        val profile = adapter.profiles[index]
        val view    = layoutInflater.inflate(R.layout.dialog_edit_profile, null)

        view.findViewById<TextView>(R.id.tv_profile_type).text = profile.displayName
        val etHost  = view.findViewById<EditText>(R.id.et_host)
        val etPort  = view.findViewById<EditText>(R.id.et_port)
        val swHttps = view.findViewById<SwitchCompat>(R.id.sw_https)

        etHost.setText(profile.host)
        etPort.setText(profile.port.toString())
        swHttps.isChecked = profile.useHttps

        if (profile.type == ConnectionType.INTERNET_MYFRITZ)
            view.findViewById<TextView>(R.id.tv_myfritz_hint)?.visibility = View.VISIBLE

        AlertDialog.Builder(this)
            .setTitle(profile.displayName)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                adapter.profiles[index] = profile.copy(
                    host     = etHost.text.toString().trim(),
                    port     = etPort.text.toString().toIntOrNull() ?: profile.port,
                    useHttps = swHttps.isChecked
                )
                adapter.notifyItemChanged(index)
                prefs.saveOrderedProfiles(adapter.profiles)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Per-profile connectivity check ────────────────────────────────────────

    private fun testProfile(index: Int) {
        val profile = adapter.profiles[index]

        if (profile.host.isBlank()) {
            Toast.makeText(this, getString(R.string.not_configured), Toast.LENGTH_SHORT).show()
            return
        }

        // Build the check dialog
        val dialogView   = layoutInflater.inflate(R.layout.dialog_connectivity_check, null)
        val tvTitle      = dialogView.findViewById<TextView>(R.id.tv_check_title)
        val llSteps      = dialogView.findViewById<LinearLayout>(R.id.ll_check_steps)
        val btnClose     = dialogView.findViewById<Button>(R.id.btn_check_close)
        val tvSummary    = dialogView.findViewById<TextView>(R.id.tv_check_summary)

        tvTitle.text = getString(R.string.checking_connection, profile.displayName)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.show()

        btnClose.setOnClickListener { dialog.dismiss() }
        btnClose.isEnabled = false

        lifecycleScope.launch {
            val steps = ConnectivityChecker.check(
                profile  = profile,
                username = prefs.fritzUsername,
                password = prefs.fritzPassword
            ) { step ->
                withContext(Dispatchers.Main) {
                    // Find or create the row for this step
                    val existingRow = llSteps.findViewWithTag<TextView>(step.label)
                    if (existingRow != null) {
                        existingRow.text = step.toString()
                        existingRow.setTextColor(stepColor(step.status))
                    } else {
                        val row = TextView(this@ConnectionProfilesActivity).also {
                            it.tag = step.label
                            it.text = step.toString()
                            it.textSize = 13f
                            it.setPadding(0, 6, 0, 6)
                            it.setTextColor(stepColor(step.status))
                        }
                        llSteps.addView(row)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                val failed = steps.count { it.status == CheckStep.Status.FAIL }
                val warned = steps.count { it.status == CheckStep.Status.WARN }
                tvSummary.visibility = View.VISIBLE
                when {
                    failed > 0 -> {
                        tvSummary.text = getString(R.string.check_result_failed, failed)
                        tvSummary.setTextColor(Color.parseColor("#CC0000"))
                    }
                    warned > 0 -> {
                        tvSummary.text = getString(R.string.check_result_warning, warned)
                        tvSummary.setTextColor(Color.parseColor("#FF8800"))
                    }
                    else -> {
                        tvSummary.text = getString(R.string.check_result_ok)
                        tvSummary.setTextColor(Color.parseColor("#007700"))
                    }
                }
                btnClose.isEnabled = true
            }
        }
    }

    private fun stepColor(status: CheckStep.Status): Int = when (status) {
        CheckStep.Status.RUNNING -> Color.parseColor("#888888")
        CheckStep.Status.OK      -> Color.parseColor("#007700")
        CheckStep.Status.WARN    -> Color.parseColor("#FF8800")
        CheckStep.Status.FAIL    -> Color.parseColor("#CC0000")
    }
}

// ── Adapter ───────────────────────────────────────────────────────────────────

class ProfileAdapter(
    val profiles: MutableList<ConnectionProfile>,
    private val onMove:      (Int, Int) -> Unit,
    private val onToggle:    (Int, Boolean) -> Unit,
    private val onEdit:      (Int) -> Unit,
    private val onTest:      (Int) -> Unit,
    private val onDragStart: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName:    TextView  = view.findViewById(R.id.tv_profile_name)
        val tvHost:    TextView  = view.findViewById(R.id.tv_profile_host)
        val cbEnabled: CheckBox  = view.findViewById(R.id.cb_enabled)
        val ivDrag:    ImageView = view.findViewById(R.id.iv_drag_handle)
        val ivEdit:    ImageView = view.findViewById(R.id.iv_edit)
        val ivTest:    ImageView = view.findViewById(R.id.iv_test)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_connection_profile, parent, false))

    override fun getItemCount() = profiles.size

    override fun onBindViewHolder(vh: VH, position: Int) {
        val p = profiles[position]
        vh.tvName.text = p.displayName
        vh.tvHost.text = if (p.host.isBlank())
            vh.itemView.context.getString(R.string.not_configured)
        else "${if (p.useHttps) "https" else "http"}://${p.host}:${p.port}"

        vh.cbEnabled.setOnCheckedChangeListener(null)
        vh.cbEnabled.isChecked = p.enabled
        vh.cbEnabled.setOnCheckedChangeListener { _, checked ->
            if (vh.bindingAdapterPosition != RecyclerView.NO_ID.toInt())
                onToggle(vh.bindingAdapterPosition, checked)
        }
        vh.ivEdit.setOnClickListener { onEdit(vh.bindingAdapterPosition) }
        vh.ivTest.setOnClickListener { onTest(vh.bindingAdapterPosition) }
        vh.ivDrag.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onDragStart(vh)
            false
        }
    }

    fun onItemMove(from: Int, to: Int) = onMove(from, to)
}

class ProfileDragCallback(private val adapter: ProfileAdapter) :
    ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
    override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean {
        adapter.onItemMove(vh.bindingAdapterPosition, t.bindingAdapterPosition); return true
    }
    override fun onSwiped(vh: RecyclerView.ViewHolder, d: Int) {}
    override fun isLongPressDragEnabled() = false
}
