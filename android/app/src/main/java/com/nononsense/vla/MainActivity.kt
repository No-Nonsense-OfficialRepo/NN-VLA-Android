package com.nononsense.vla

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.Settings
import android.net.Uri
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.ScrollView
import com.nononsense.vla.discovery.LanHostDiscovery
import com.nononsense.vla.model.AppPreferences
import com.nononsense.vla.model.RoomKey
import com.nononsense.vla.model.TunnelState
import com.nononsense.vla.model.TunnelStateStore
import com.nononsense.vla.vpn.VlaVpnService

class MainActivity : Activity() {
    private val nn by lazy { NnTheme(this) }
    private val versionName by lazy {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }
    private lateinit var roomKeyInput: EditText
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var usersText: TextView
    private lateinit var autoJoinButton: Button
    private lateinit var fileShareSwitch: Switch
    private lateinit var diagnosticsButton: Button
    private lateinit var copyShareLink: Button
    private val hostDiscovery by lazy { LanHostDiscovery(applicationContext) }
    private var pendingRoomKey: RoomKey? = null
    private var expectedHostKey: String? = null
    private var discoveredLanAddress: String? = null
    private enum class NetworkAction { JOIN, DISCOVER }
    private var pendingNetworkAction: NetworkAction? = null
    private var settingShareSwitch = false
    private var passwordDialog: AlertDialog? = null
    private var passwordPrompt: String? = null
    private var joinSource: String? = null
    private var diagnosticReport: String? = null
    private val stateObserver: (TunnelState) -> Unit = { renderState(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnosticReport = savedInstanceState?.getString("diagnostic_report")
        joinSource = savedInstanceState?.getString("join_source")
        pendingRoomKey = RoomKey.parse(savedInstanceState?.getString("pending_room").orEmpty()).getOrNull()
        expectedHostKey = savedInstanceState?.getString("host_key")
        discoveredLanAddress = savedInstanceState?.getString("host_lan")
        pendingNetworkAction = NetworkAction.entries.firstOrNull {
            it.name == savedInstanceState?.getString("pending_network_action")
        }
        setContentView(buildContent())
        savedInstanceState?.getString("room_input")?.let { roomKeyInput.setText(it) }
        TunnelStateStore.observe(stateObserver)
        if (savedInstanceState?.getBoolean("discovering") == true &&
            (TunnelStateStore.current == TunnelState.Disconnected || TunnelStateStore.current is TunnelState.Failed)) {
            withLocalNetworkAccess(NetworkAction.DISCOVER)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("diagnostic_report", diagnosticReport)
        outState.putString("join_source", joinSource)
        outState.putString("pending_room", pendingRoomKey?.value)
        outState.putString("host_key", expectedHostKey)
        outState.putString("host_lan", discoveredLanAddress)
        outState.putString("room_input", roomKeyInput.text.toString())
        outState.putString("pending_network_action", pendingNetworkAction?.name)
        outState.putBoolean("discovering", discovering)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        TunnelStateStore.removeObserver(stateObserver)
        if (discovering) hostDiscovery.cancel()
        passwordDialog?.dismiss()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val sharing = AppPreferences.isFileSharingEnabled(this)
        if (sharing && !hasFileAccess()) setFileSharing(false)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCAL_NETWORK_REQUEST) {
            val action = pendingNetworkAction
            pendingNetworkAction = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) action?.let(::runNetworkAction)
            else statusText.text = "Allow local network access in Android settings to reach the Host."
            return
        }
        if (requestCode == FILE_PERMISSION_REQUEST) setFileSharing(hasFileAccess())
    }

    @Deprecated("VpnService permission uses the activity-result contract")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_SETTINGS_REQUEST) {
            setFileSharing(hasFileAccess())
            return
        }
        if (requestCode == EXPORT_DIAGNOSTICS_REQUEST) {
            val uri = data?.data
            val report = diagnosticReport
            if (resultCode == RESULT_OK && uri != null && report != null) {
                val saved = runCatching {
                    checkNotNull(contentResolver.openOutputStream(uri)).use { it.write(report.toByteArray(Charsets.UTF_8)) }
                }.isSuccess
                android.widget.Toast.makeText(this, if (saved) "Diagnostics saved" else "Could not save diagnostics", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (requestCode != VPN_PERMISSION_REQUEST) return
        val roomKey = pendingRoomKey
        pendingRoomKey = null
        if (resultCode == RESULT_OK && roomKey != null) {
            startConnection(roomKey)
        } else {
            statusText.text = "VPN permission is required to join the room."
        }
    }

    private fun connect() {
        val roomKey = RoomKey.parse(roomKeyInput.text.toString()).getOrElse {
            roomKeyInput.error = it.message
            return
        }
        roomKeyInput.setText(roomKey.displayValue)
        pendingRoomKey = roomKey
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) {
            pendingRoomKey = null
            startConnection(roomKey)
        } else {
            @Suppress("DEPRECATION")
            startActivityForResult(permissionIntent, VPN_PERMISSION_REQUEST)
        }
    }

    private fun startConnection(roomKey: RoomKey) {
        AppPreferences.saveRoomKey(this, roomKey)
        startForegroundService(VlaVpnService.connectIntent(this, roomKey, expectedHostKey, discoveredLanAddress))
    }

    private fun withLocalNetworkAccess(action: NetworkAction) {
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            pendingNetworkAction = action
            requestPermissions(arrayOf(LOCAL_NETWORK_PERMISSION), LOCAL_NETWORK_REQUEST)
        } else runNetworkAction(action)
    }

    private fun runNetworkAction(action: NetworkAction) {
        when (action) {
            NetworkAction.JOIN -> {
                joinSource = null
                expectedHostKey = null
                discoveredLanAddress = null
                connect()
            }
            NetworkAction.DISCOVER -> autoJoin()
        }
    }

    private var discovering = false
    private fun autoJoin() {
        if (discovering) return
        expectedHostKey = null
        discoveredLanAddress = null
        discovering = true
        autoJoinButton.isEnabled = false
        actionButton.isEnabled = false
        statusText.text = "Searching this Wi-Fi network for a VLA Host…"
        hostDiscovery.search(
            onFound = { host ->
                if (isDestroyed || isFinishing) return@search
                discovering = false
                autoJoinButton.isEnabled = true
                actionButton.isEnabled = true
                RoomKey.parse(host.roomKey).getOrNull()?.let {
                    expectedHostKey = host.hostPublicKey
                    discoveredLanAddress = host.presenceAddress
                    roomKeyInput.setText(it.displayValue)
                    joinSource = "Host found on Wi-Fi: ${host.name}"
                    connect()
                }
            },
            onFailure = { message ->
                if (isDestroyed || isFinishing) return@search
                discovering = false
                autoJoinButton.isEnabled = true
                actionButton.isEnabled = true
                val saved = AppPreferences.roomKey(this)
                if (saved != null) {
                    roomKeyInput.setText(saved.displayValue)
                    joinSource = "Host search failed. Using saved room."
                    connect()
                } else {
                    statusText.text = message
                }
            },
        )
    }

    private fun disconnect() {
        joinSource = null
        startService(VlaVpnService.disconnectIntent(this))
    }

    private fun renderState(state: TunnelState) {
        showPasswordPrompt((state as? TunnelState.Connected)?.passwordPrompt)
        copyShareLink.visibility = if ((state as? TunnelState.Connected)?.shareUrl != null) View.VISIBLE else View.GONE
        when (state) {
            TunnelState.Disconnected -> { usersText.text = "Join a room to see its users."; configureUi("Ready to join a VLA room", "Join Room", true, ::connect) }
            is TunnelState.Connecting -> { usersText.text = "Waiting for the room roster…"; configureUi(listOfNotNull(joinSource, "Connecting to ${state.roomKey}…").joinToString("\n"), "Cancel", false, ::disconnect) }
            is TunnelState.Connected -> {
                usersText.text = if (state.users.isEmpty()) state.presenceStatus else state.users.joinToString("\n\n") { user ->
                    val role = if (user.isHost) "HOST" else user.state.uppercase()
                    val ping = user.pingMs?.let { "  •  ${it} ms" }.orEmpty()
                    "${user.name}  •  $role\n${user.address}$ping"
                }
                configureUi(
                    listOfNotNull(joinSource, "VPN address: ${state.address}", state.shareUrl?.let { "Files: $it" },
                        state.sharingError).joinToString("\n"),
                    "Leave Room", false, ::disconnect,
                )
            }
            is TunnelState.Failed -> { usersText.text = "Join a room to see its users."; configureUi(state.message, "Retry", true, ::connect) }
        }
    }

    private fun configureUi(status: String, action: String, inputEnabled: Boolean, onClick: () -> Unit) {
        statusText.text = status
        val (icon, accent) = when (action) {
            "Leave Room" -> R.drawable.nn_room_leave to R.color.nn_red
            "Cancel" -> R.drawable.nn_cancel to R.color.nn_warning
            "Retry" -> R.drawable.nn_refresh to R.color.nn_warning
            else -> R.drawable.nn_key to R.color.nn_green
        }
        nn.styleAction(actionButton, action, icon, accent)
        actionButton.isEnabled = !discovering
        actionButton.setOnClickListener {
            if (inputEnabled) withLocalNetworkAccess(NetworkAction.JOIN) else onClick()
        }
        roomKeyInput.isEnabled = inputEnabled
        autoJoinButton.isEnabled = inputEnabled && !discovering
    }

    private fun buildContent(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(nn.dp(16), nn.dp(12), nn.dp(16), nn.dp(16))
        }

        fun LinearLayout.addRow(view: View, top: Int = 0, height: Int = -2) {
            addView(view, LinearLayout.LayoutParams(-1, if (height < 0) height else nn.dp(height)).apply {
                topMargin = nn.dp(top)
            })
        }

        fun heading(text: String, size: Float = 13f) = TextView(this).apply {
            this.text = text
            textSize = size
            typeface = nn.heading
            setTextColor(nn.color(R.color.nn_paper))
        }

        fun panel() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = nn.surface(R.color.nn_panel)
            setPadding(nn.dp(12), nn.dp(10), nn.dp(12), nn.dp(12))
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(runCatching {
                    assets.open("nn/no-nonsense-monogram.png").use { Drawable.createFromStream(it, "NN monogram") }
                }.getOrNull() ?: getDrawable(R.drawable.nn_launcher))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(nn.dp(44), nn.dp(36)))
            addView(heading("VLA", 27f), LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = nn.dp(6)
            })
            addView(TextView(this@MainActivity).apply {
                text = "v$versionName"
                textSize = 11f
                typeface = nn.body
                setTextColor(nn.color(R.color.nn_secondary))
            })
        }
        page.addRow(header)
        page.addRow(View(this).apply { setBackgroundColor(nn.color(R.color.nn_border)) }, top = 8, height = 1)

        val roomPanel = panel()
        roomPanel.addRow(heading("Join Room"))
        autoJoinButton = nn.button("Join Host Automatically", R.drawable.nn_search, R.color.nn_green).apply {
            setOnClickListener { withLocalNetworkAccess(NetworkAction.DISCOVER) }
        }
        roomPanel.addRow(autoJoinButton, top = 6)
        roomPanel.addRow(TextView(this).apply {
            id = View.generateViewId()
            text = "Room key"
            textSize = 12f
            typeface = nn.body
            setTextColor(nn.color(R.color.nn_secondary))
        }.also { label ->
            roomKeyInput = EditText(this).apply {
                id = View.generateViewId()
                label.labelFor = id
                hint = "0000-0000-0000-0000"
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                isSingleLine = true
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                typeface = Typeface.MONOSPACE
                imeOptions = EditorInfo.IME_ACTION_GO
                setTextColor(nn.color(R.color.nn_paper))
                setHintTextColor(nn.color(R.color.nn_muted))
                background = nn.surface(R.color.nn_content)
                setPadding(nn.dp(12), nn.dp(9), nn.dp(12), nn.dp(9))
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId != EditorInfo.IME_ACTION_GO || !isEnabled || !actionButton.isEnabled) false
                    else {
                        withLocalNetworkAccess(NetworkAction.JOIN)
                        true
                    }
                }
                AppPreferences.roomKey(this@MainActivity)?.let { setText(it.displayValue) }
            }
        }, top = 8)
        roomPanel.addRow(roomKeyInput, top = 4, height = 48)
        actionButton = nn.button("Join Room", R.drawable.nn_key, R.color.nn_green)
        roomPanel.addRow(actionButton, top = 4)
        statusText = TextView(this).apply {
            typeface = nn.body
            textSize = 13f
            setTextColor(nn.color(R.color.nn_secondary))
            setTextIsSelectable(true)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        roomPanel.addRow(statusText, top = 10)
        page.addRow(roomPanel, top = 12)

        val rosterPanel = panel()
        val rosterHeading = heading("Room users").apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.nn_people, 0, 0, 0)
            compoundDrawablePadding = nn.dp(7)
        }
        rosterPanel.addRow(rosterHeading)
        usersText = TextView(this).apply {
            typeface = nn.body
            textSize = 13f
            setTextColor(nn.color(R.color.nn_secondary))
            setTextIsSelectable(true)
            setLineSpacing(nn.dp(3).toFloat(), 1f)
        }
        rosterPanel.addRow(usersText, top = 8)
        page.addRow(rosterPanel, top = 10)

        val toolsPanel = panel()
        toolsPanel.addRow(heading("Room tools"))
        fileShareSwitch = Switch(this).apply {
            text = "Share phone files (read/write)"
            typeface = nn.body
            textSize = 13f
            minHeight = nn.dp(48)
            setTextColor(nn.color(R.color.nn_paper))
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(nn.color(R.color.nn_brass), nn.color(R.color.nn_secondary)),
            )
            isChecked = hasFileAccess() && AppPreferences.isFileSharingEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, enabled ->
                if (settingShareSwitch) return@setOnCheckedChangeListener
                if (enabled && !hasFileAccess()) {
                    setFileSharing(false)
                    requestFileAccess()
                } else setFileSharing(enabled)
            }
        }
        toolsPanel.addRow(fileShareSwitch)
        copyShareLink = nn.button("Copy file link", R.drawable.nn_info).apply {
            visibility = View.GONE
            setOnClickListener {
                val url = (TunnelStateStore.current as? TunnelState.Connected)?.shareUrl ?: return@setOnClickListener
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Phone files", url))
            }
        }
        toolsPanel.addRow(copyShareLink)
        diagnosticsButton = nn.button("Run Network Diagnostics", R.drawable.nn_info).apply {
            setOnClickListener { runDiagnostics() }
        }
        toolsPanel.addRow(diagnosticsButton)
        page.addRow(toolsPanel, top = 10)
        return ScrollView(this).apply {
            setBackgroundColor(nn.color(R.color.nn_dark))
            isFillViewport = true
            addView(page)
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
                setOnApplyWindowInsetsListener { view, insets ->
                    val padding = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                    view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
                    insets
                }
            }
        }
    }

    private fun runDiagnostics() {
        val discovery = hostDiscovery.lastResult
        val summary = "NN VLA $versionName · Android API ${Build.VERSION.SDK_INT}\n${java.time.Instant.now()}\nAuto Find: $discovery"
        if (TunnelStateStore.current !is TunnelState.Connected) {
            showDiagnostics("$summary\nRoom status: ${statusText.text}")
            return
        }
        diagnosticsButton.isEnabled = false
        startService(VlaVpnService.diagnosticsIntent(this, object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, data: Bundle?) {
                if (isDestroyed || isFinishing) return
                diagnosticsButton.isEnabled = true
                showDiagnostics("$summary\n${data?.getString(VlaVpnService.EXTRA_REPORT).orEmpty()}")
            }
        }))
    }

    private fun showDiagnostics(report: String) {
        diagnosticReport = report
        AlertDialog.Builder(this)
            .setTitle("Network diagnostics")
            .setMessage(report)
            .setPositiveButton("Save log") { _, _ ->
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "NN-VLA-diagnostics.txt")
                @Suppress("DEPRECATION")
                startActivityForResult(intent, EXPORT_DIAGNOSTICS_REQUEST)
            }
            .setNegativeButton("Share") { _, _ ->
                val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                startActivity(Intent.createChooser(intent, "Share network diagnostics"))
            }
            .setNeutralButton("Close", null)
            .show()
    }

    private fun hasFileAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun setFileSharing(enabled: Boolean) {
        settingShareSwitch = true
        try { fileShareSwitch.isChecked = enabled } finally { settingShareSwitch = false }
        AppPreferences.setFileSharingEnabled(this, enabled)
        if (TunnelStateStore.current is TunnelState.Connected) startService(VlaVpnService.refreshShareIntent(this))
    }

    private fun showPasswordPrompt(message: String?) {
        if (message == passwordPrompt) return
        passwordDialog?.dismiss()
        passwordDialog = null
        passwordPrompt = message
        if (message == null || isFinishing) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Room password"
        }
        passwordDialog = AlertDialog.Builder(this)
            .setTitle("Protected room")
            .setMessage(message)
            .setView(input)
            .setPositiveButton("Join") { _, _ -> startService(VlaVpnService.passwordIntent(this, input.text.toString())) }
            .setNegativeButton("Leave") { _, _ -> disconnect() }
            .setOnCancelListener { disconnect() }
            .show()
    }

    private fun requestFileAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")), FILE_SETTINGS_REQUEST)
        } else {
            requestPermissions(
                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                FILE_PERMISSION_REQUEST,
            )
        }
    }

    companion object {
        private const val VPN_PERMISSION_REQUEST = 147
        private const val LOCAL_NETWORK_REQUEST = 149
        private const val EXPORT_DIAGNOSTICS_REQUEST = 150
        private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val FILE_PERMISSION_REQUEST = 148
        private const val FILE_SETTINGS_REQUEST = 151
    }
}
