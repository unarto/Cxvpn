package com.v2ray.ang.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.extension.toTrafficString

class MainActivity : HelperBaseActivity() {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            setupGroupTab()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, getString(R.string.title_server))

        // setup viewpager and tablayout
        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true

        binding.bottomNav.itemIconTintList = null
        binding.bottomNav.selectedItemId = R.id.nav_proxies
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    supportActionBar?.title = "Dashboard"
                    binding.layoutDashboard.visibility = android.view.View.VISIBLE
                    binding.layoutProxies.visibility = android.view.View.GONE
                    true
                }
                R.id.nav_proxies -> {
                    supportActionBar?.title = "Proxies"
                    binding.layoutDashboard.visibility = android.view.View.GONE
                    binding.layoutProxies.visibility = android.view.View.VISIBLE
                    true
                }
                R.id.nav_profiles -> {
                    startActivity(Intent(this, SubSettingActivity::class.java))
                    overridePendingTransition(0, 0)
                    false
                }
                R.id.nav_tools -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    overridePendingTransition(0, 0)
                    false
                }
                else -> false
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        binding.btnRoutingMode.setOnClickListener {
            startActivity(Intent(this, RoutingSettingActivity::class.java))
        }

        binding.btnVpnMode.setOnClickListener {
            val isChecked = !binding.switchVpnMode.isChecked
            binding.switchVpnMode.isChecked = isChecked
            val newMode = if (isChecked) com.v2ray.ang.AppConfig.VPN else "Proxy only"
            com.v2ray.ang.handler.MmkvManager.encodeSettings(com.v2ray.ang.AppConfig.PREF_MODE, newMode)
            binding.tvVpnMode.text = if (isChecked) "Mode VPN" else "Mode Proxy"
        }

        binding.fab.setOnClickListener { handleFabAction() }
        binding.layoutTest.setOnClickListener { handleLayoutTestClick() }

        setupGroupTab()
        setupViewModel()
        mainViewModel.reloadServerList()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
    }

    private fun getIpAddress(type: String): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            val list = java.util.Collections.list(interfaces)
            // First pass: try with standard filters (e.g. wlan, rmnet, eth)
            for (intf in list) {
                val nameLower = intf.name.lowercase()
                val match = if (type == "tun") {
                    nameLower.contains("tun")
                } else {
                    !nameLower.contains("tun") && (nameLower.contains("wlan") || nameLower.contains("rmnet") || nameLower.contains("eth") || nameLower.contains("ccmni") || nameLower.contains("pdp"))
                }
                if (match) {
                    for (addr in java.util.Collections.list(intf.inetAddresses)) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            return addr.hostAddress ?: ""
                        }
                    }
                }
            }

            // Second pass for "net": if no specific mobile/wifi name matched, look for any non-tunnel local IP
            if (type == "net") {
                for (intf in list) {
                    val nameLower = intf.name.lowercase()
                    if (!nameLower.contains("tun") && !nameLower.contains("lo") && !nameLower.contains("p2p") && !nameLower.contains("tap")) {
                        for (addr in java.util.Collections.list(intf.inetAddresses)) {
                            if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                                return addr.hostAddress ?: ""
                            }
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return ""
    }

    private var lastIpRefreshTime = 0L
    private var isRefreshingIps = false

    private fun refreshIpAddresses(forceFetchPublic: Boolean = false) {
        if (isRefreshingIps) return
        val currentTime = System.currentTimeMillis()
        val shouldFetchPublic = forceFetchPublic || (currentTime - lastIpRefreshTime) > 10000L

        isRefreshingIps = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Read local properties safely on Dispatchers.IO
                val providerIp = getIpAddress("net")
                val tunIp = getIpAddress("tun")

                var publicIp = ""
                if (shouldFetchPublic) {
                    val providers = listOf(
                        "https://api.ipify.org",
                        "https://ipinfo.io/ip",
                        "https://icanhazip.com",
                        "https://ifconfig.me/ip"
                    )
                    for (provider in providers) {
                        try {
                            val url = java.net.URL(provider)
                            val connection = url.openConnection()
                            connection.connectTimeout = 3000
                            connection.readTimeout = 3000
                            val ip = connection.getInputStream().bufferedReader().use { it.readText() }.trim()
                            if (ip.isNotEmpty() && ip.contains(".")) {
                                publicIp = ip
                                lastIpRefreshTime = System.currentTimeMillis()
                                break
                            }
                        } catch (e: Exception) {
                            // Try next provider
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    binding.tvProviderIp.text = if (providerIp.isNotEmpty()) providerIp else "-"
                    if (shouldFetchPublic) {
                        binding.tvNetworkIp.text = if (publicIp.isNotEmpty()) {
                            publicIp
                        } else if (tunIp.isNotEmpty()) {
                            tunIp
                        } else {
                            "-"
                        }
                    } else {
                        if (binding.tvNetworkIp.text == "-" || binding.tvNetworkIp.text.isEmpty()) {
                            binding.tvNetworkIp.text = if (tunIp.isNotEmpty()) tunIp else "-"
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isRefreshingIps = false
            }
        }
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            applyRunningState(false, isRunning)
            if (isRunning) {
                lifecycleScope.launch {
                    delay(2500)
                    refreshIpAddresses(forceFetchPublic = true)
                }
            } else {
                refreshIpAddresses(forceFetchPublic = true)
            }
        }
        mainViewModel.updateTrafficAction.observe(this) { traffic ->
            binding.tvNetworkSpeed.text = "↑ ${traffic.txSpeed.toSpeedString()}   ↓ ${traffic.rxSpeed.toSpeedString()}"
            binding.tvTrafficUsage.text = "↑ ${traffic.totalTx.toTrafficString()}\n↓ ${traffic.totalRx.toTrafficString()}"
            binding.tvMemoryInfo.text = "${traffic.appMemory} MB"
            
            // Periodically refresh IP addresses without spamming HTTP calls (throttle built-in)
            refreshIpAddresses(forceFetchPublic = false)
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun setupGroupTab() {
        val groups = mainViewModel.getSubscriptions(this)
        groupPagerAdapter.update(groups)

        tabMediator?.detach()
        tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position ->
            groupPagerAdapter.groups.getOrNull(position)?.let {
                tab.text = it.remarks
                tab.tag = it.id
            }
        }.also { it.attach() }

        val targetIndex = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }.takeIf { it >= 0 } ?: maxOf(0, groups.size - 1)
        if (groups.isNotEmpty()) {
            binding.viewPager.setCurrentItem(targetIndex, false)
        }

        binding.tabGroup.isVisible = groups.isNotEmpty()
    }

    private fun handleFabAction() {
        applyRunningState(isLoading = true, isRunning = false)

        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        } else if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            setTestState(getString(R.string.connection_test_testing))
            mainViewModel.testCurrentServerRealPing()
        } else {
            // service not running: keep existing no-op (could show a message if desired)
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        V2RayServiceManager.startVService(this)
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    private  fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        if (isLoading) {
            binding.fab.setImageResource(R.drawable.ic_fab_check)
            return
        }

        if (isRunning) {
            binding.fab.setImageResource(R.drawable.ic_stop_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
            binding.fab.contentDescription = getString(R.string.action_stop_service)
            setTestState(getString(R.string.connection_connected))
            binding.layoutTest.isFocusable = true
        } else {
            binding.fab.setImageResource(R.drawable.ic_play_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
            binding.fab.contentDescription = getString(R.string.tasker_start_service)
            setTestState(getString(R.string.connection_not_connected))
            binding.layoutTest.isFocusable = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        
        val navGoTo = intent.getStringExtra("NAV_GO_TO")
        if (navGoTo == "dashboard") {
            binding.bottomNav.selectedItemId = R.id.nav_dashboard
            binding.layoutDashboard.visibility = android.view.View.VISIBLE
            binding.layoutProxies.visibility = android.view.View.GONE
        } else if (navGoTo == "proxies") {
            binding.bottomNav.selectedItemId = R.id.nav_proxies
            binding.layoutDashboard.visibility = android.view.View.GONE
            binding.layoutProxies.visibility = android.view.View.VISIBLE
        } else {
            if (binding.layoutDashboard.visibility == android.view.View.VISIBLE) {
                binding.bottomNav.selectedItemId = R.id.nav_dashboard
            } else if (binding.layoutProxies.visibility == android.view.View.VISIBLE) {
                binding.bottomNav.selectedItemId = R.id.nav_proxies
            }
        }
        
        // Clear the intent extra so it doesn't trigger again on normal resume
        intent.removeExtra("NAV_GO_TO")

        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val currentDateStr = formatter.format(java.util.Date())
        val savedDate = com.v2ray.ang.handler.MmkvManager.decodeSettingsString("Traffic_Date", "")
        if (currentDateStr == savedDate) {
            val todayTx = com.v2ray.ang.handler.MmkvManager.decodeSettingsLong("Traffic_Tx", 0L)
            val todayRx = com.v2ray.ang.handler.MmkvManager.decodeSettingsLong("Traffic_Rx", 0L)
            binding.tvTrafficUsage.text = "↑ ${todayTx.toTrafficString()}\n↓ ${todayRx.toTrafficString()}"
        } else {
            binding.tvTrafficUsage.text = "↑ 0 B\n↓ 0 B"
        }

        if (mainViewModel.isRunning.value != true) {
            val memInfo = android.os.Debug.MemoryInfo()
            android.os.Debug.getMemoryInfo(memInfo)
            binding.tvMemoryInfo.text = "${memInfo.totalPss / 1024L} MB"
        }
        
        refreshIpAddresses(forceFetchPublic = true)

        val routingMode = com.v2ray.ang.handler.MmkvManager.decodeSettingsString(com.v2ray.ang.AppConfig.PREF_ROUTING_DOMAIN_STRATEGY) ?: "IPIfNonMatch"
        binding.tvRoutingMode.text = routingMode
        
        val mode = com.v2ray.ang.handler.MmkvManager.decodeSettingsString(com.v2ray.ang.AppConfig.PREF_MODE) ?: com.v2ray.ang.AppConfig.VPN
        val isVpn = mode == com.v2ray.ang.AppConfig.VPN
        binding.switchVpnMode.isChecked = isVpn
        binding.tvVpnMode.text = if (isVpn) "Mode VPN" else "Mode Proxy"
        
        mainViewModel.reloadServerList()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    mainViewModel.filterConfig(newText.orEmpty())
                    return false
                }
            })

            searchView.setOnCloseListener {
                mainViewModel.filterConfig("")
                false
            }
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)
        val isGrid = com.v2ray.ang.handler.MmkvManager.decodeSettingsBool(com.v2ray.ang.AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)
        menu.findItem(R.id.style_grid)?.isChecked = isGrid
        menu.findItem(R.id.style_list)?.isChecked = !isGrid

        when (com.v2ray.ang.handler.MmkvManager.decodeSettingsString("pref_server_sort", "default")) {
            "delay" -> menu.findItem(R.id.sort_delay)?.isChecked = true
            "name" -> menu.findItem(R.id.sort_name)?.isChecked = true
            else -> menu.findItem(R.id.sort_default)?.isChecked = true
        }

        when (com.v2ray.ang.handler.MmkvManager.decodeSettingsString("pref_server_layout", "standard")) {
            "loose" -> menu.findItem(R.id.layout_loose)?.isChecked = true
            "tight" -> menu.findItem(R.id.layout_tight)?.isChecked = true
            else -> menu.findItem(R.id.layout_standard)?.isChecked = true
        }

        when (com.v2ray.ang.handler.MmkvManager.decodeSettingsString("pref_server_size", "standard")) {
            "shrink" -> menu.findItem(R.id.size_shrink)?.isChecked = true
            "min" -> menu.findItem(R.id.size_min)?.isChecked = true
            else -> menu.findItem(R.id.size_standard)?.isChecked = true
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode()
            true
        }

        R.id.hotshare -> {
            startActivity(Intent(this, HotshareActivity::class.java))
            true
        }

        R.id.import_clipboard -> {
            importClipboard()
            true
        }

        R.id.import_local -> {
            importConfigLocal()
            true
        }

        R.id.import_manually_policy_group -> {
            importManually(EConfigType.POLICYGROUP.value)
            true
        }

        R.id.import_manually_vmess -> {
            importManually(EConfigType.VMESS.value)
            true
        }

        R.id.import_manually_vless -> {
            importManually(EConfigType.VLESS.value)
            true
        }

        R.id.import_manually_ss -> {
            importManually(EConfigType.SHADOWSOCKS.value)
            true
        }

        R.id.import_manually_socks -> {
            importManually(EConfigType.SOCKS.value)
            true
        }

        R.id.import_manually_http -> {
            importManually(EConfigType.HTTP.value)
            true
        }

        R.id.import_manually_trojan -> {
            importManually(EConfigType.TROJAN.value)
            true
        }

        R.id.import_manually_wireguard -> {
            importManually(EConfigType.WIREGUARD.value)
            true
        }

        R.id.import_manually_hysteria2 -> {
            importManually(EConfigType.HYSTERIA2.value)
            true
        }

        R.id.import_manually_hysteriaudp -> {
            importManually(EConfigType.HYSTERIAUDP.value)
            true
        }

        R.id.delete_invalid -> {
            mainViewModel.removeInvalidServer()
            true
        }

        R.id.delete_all -> {
            AlertDialog.Builder(this)
                .setMessage(R.string.del_all_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    mainViewModel.removeAllServer()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        R.id.export_all -> {
            val servers = mainViewModel.serversCache
            if (servers.isNotEmpty()) {
                val sb = StringBuilder()
                for (server in servers) {
                    sb.append(Utils.getURL(server)).append("\n")
                }
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("v2ray_configs", sb.toString())
                clipboard.setPrimaryClip(clip)
                toast(R.string.copied_to_clipboard)
            }
            true
        }

        R.id.hotspot_root -> {
            lifecycleScope.launch(Dispatchers.IO) {
                if (Shell.getShell().isRoot) {
                    val result = Shell.cmd(
                        "iptables -t filter -F FORWARD",
                        "iptables -t nat -F POSTROUTING",
                        "iptables -t filter -I FORWARD -j ACCEPT",
                        "iptables -t nat -I POSTROUTING -j MASQUERADE",
                        "ip rule add pref 1 from all lookup main",
                        "ip rule add pref 1 from all lookup default",
                        "ip route add default dev tun0"
                    ).exec()
                    withContext(Dispatchers.Main) {
                        if (result.isSuccess) {
                            toast("Hotspot Root Activated Successfully!")
                        } else {
                            toast("Failed to activate Hotspot Root")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        toast("Root access is required!")
                    }
                }
            }
            true
        }

        R.id.ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllTcping()
            true
        }

        R.id.real_ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllRealPing()
            true
        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }

        R.id.style_grid -> {
            item.isChecked = true
            com.v2ray.ang.handler.MmkvManager.encodeSettings(com.v2ray.ang.AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, true)
            mainViewModel.uiRefreshAction.value = true
            true
        }

        R.id.style_list -> {
            item.isChecked = true
            com.v2ray.ang.handler.MmkvManager.encodeSettings(com.v2ray.ang.AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)
            mainViewModel.uiRefreshAction.value = true
            true
        }

        R.id.sort_default -> { item.isChecked = true; com.v2ray.ang.handler.MmkvManager.encodeSettings("pref_server_sort", "default"); mainViewModel.reloadServerList(); true }
        R.id.sort_delay -> { item.isChecked = true; com.v2ray.ang.handler.MmkvManager.encodeSettings("pref_server_sort", "delay"); mainViewModel.reloadServerList(); true }
        R.id.sort_name -> { item.isChecked = true; com.v2ray.ang.handler.MmkvManager.encodeSettings("pref_server_sort", "name"); mainViewModel.reloadServerList(); true }

        R.id.layout_loose -> { item.isChecked = true; com.v2ray.ang.handler.MmkvManager.encodeSettings("pref_server_layout", "loose"); mainViewModel.reloadServerList(); true }
        R.id.layout_standard -> { item.isChecked = true; com.v2ray.ang.handler.MmkvManager.encodeSettings("pref_server_layout", "standard"); mainViewModel.reloadServerList(); true }
        R.id.layout_tight -> { item.isChecked = true; com.v2ray.ang.handler.MmkvManager.encodeSettings("pref_server_layout", "tight"); mainViewModel.reloadServerList(); true }

        R.id.size_standard -> { item.isChecked = true; com.v2ray.ang.handler.MmkvManager.encodeSettings("pref_server_size", "standard"); mainViewModel.reloadServerList(); true }
        R.id.size_shrink -> { item.isChecked = true; com.v2ray.ang.handler.MmkvManager.encodeSettings("pref_server_size", "shrink"); mainViewModel.reloadServerList(); true }
        R.id.size_min -> { item.isChecked = true; com.v2ray.ang.handler.MmkvManager.encodeSettings("pref_server_size", "min"); mainViewModel.reloadServerList(); true }

        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        if (createConfigType == EConfigType.POLICYGROUP.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerGroupActivity::class.java)
            )
        } else {
            startActivity(
                Intent()
                    .putExtra("createConfigType", createConfigType)
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerActivity::class.java)
            )
        }
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importBatchConfig(scanResult)
            }
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                        }

                        countSub > 0 -> setupGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    hideLoading()
                }
                Log.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }


    /**
     * import config from sub
     */
    private fun importConfigViaSub(): Boolean {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            val count = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (count > 0) {
                    toast(getString(R.string.title_update_config_count, count))
                    mainViewModel.reloadServerList()
                } else {
                    toastError(R.string.toast_failure)
                }
                hideLoading()
            }
        }
        return true
    }

    private fun exportAll() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    toast(getString(R.string.title_export_config_count, ret))
                else
                    toastError(R.string.toast_failure)
                hideLoading()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_duplicate_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun sortByTestResults() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                hideLoading()
            }
        }
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }

            readContentFromUri(uri)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { input ->
                importBatchConfig(input?.bufferedReader()?.readText())
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to read content from URI", e)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    override fun onDestroy() {
        tabMediator?.detach()
        super.onDestroy()
    }
}