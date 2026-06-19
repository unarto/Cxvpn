package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityHotshareBinding
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.AppConfig

class HotshareActivity : BaseActivity() {
    private lateinit var binding: ActivityHotshareBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHotshareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Hotshare"

        updateProxyInfo()
        updateHotspotInfoViaRoot()

        val autoOff = MmkvManager.decodeSettingsBool("pref_hotshare_auto_off", true)
        binding.cbAutoTurnOff.isChecked = autoOff
        binding.cbAutoTurnOff.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings("pref_hotshare_auto_off", isChecked)
        }

        val isSharing = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING) == true
        binding.btnStartHotshare.text = if (isSharing) "STOP HOTSHARE" else "START HOTSHARE"

        binding.btnStartHotshare.setOnClickListener {
            val sharing = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING) == true
            MmkvManager.encodeSettings(AppConfig.PREF_PROXY_SHARING, !sharing)
            binding.btnStartHotshare.text = if (!sharing) "STOP HOTSHARE" else "START HOTSHARE"
            
            // Restart V2Ray to apply proxy sharing
            if (V2RayServiceManager.isRunning()) {
                val intent = Intent(AppConfig.BROADCAST_ACTION_ACTIVITY)
                intent.putExtra("key", AppConfig.MSG_STATE_RESTART)
                sendBroadcast(intent)
            }
        }
    }

    private fun updateProxyInfo() {
        val httpPort = MmkvManager.decodeSettingsString(AppConfig.PREF_HTTP_PORT) ?: "10809"
        val socksPort = MmkvManager.decodeSettingsString(AppConfig.PREF_SOCKS_PORT) ?: "10808"
        
        binding.tvProxyPortHttp.text = httpPort
        binding.tvProxyPortSocks.text = socksPort
        
        val ip = getIpAddress("wlan")
        binding.tvProxyIp.text = if(ip.isNotEmpty()) ip else "-"
    }
    
    private fun updateHotspotInfoViaRoot() {
        lifecycleScope.launch(Dispatchers.IO) {
            val ssidAndPass = getHotspotInfoViaRoot()
            withContext(Dispatchers.Main) {
                if (ssidAndPass != null) {
                    binding.tvSsid.text = ssidAndPass.first
                    binding.tvPassword.text = if(ssidAndPass.second.isNotEmpty()) ssidAndPass.second else "None"
                } else {
                    binding.tvSsid.text = "Tidak diketahui (butuh root)"
                    binding.tvPassword.text = "Tidak diketahui"
                }
            }
        }
    }

    private fun getHotspotInfoViaRoot(): Pair<String, String>? {
        if (!com.topjohnwu.superuser.Shell.getShell().isRoot) return null
        
        var ssid = ""
        var pass = ""
        try {
            val res = com.topjohnwu.superuser.Shell.cmd("dumpsys wifi | grep -E 'mSsid|SSID|mPassphrase|Passphrase'").exec().out
            for (line in res) {
                val trimmed = line.trim()
                if (trimmed.startsWith("SSID:") || trimmed.startsWith("mSsid=")) {
                    val s = trimmed.substringAfter(":").substringAfter("=").trim().removeSurrounding("\"")
                    if (s.isNotEmpty()) ssid = s
                }
                if (trimmed.startsWith("Passphrase:") || trimmed.startsWith("mPassphrase=")) {
                    val p = trimmed.substringAfter(":").substringAfter("=").trim().removeSurrounding("\"")
                    pass = if (p == "<null>") "" else p
                }
            }
            if (ssid.isEmpty() || pass.isEmpty()) {
                val xml1 = com.topjohnwu.superuser.Shell.cmd("cat /data/misc/apexdata/com.android.wifi/WifiConfigStoreSoftAp.xml").exec().out.joinToString("\n")
                val ssidMatch = Regex("""<string name="SSID">&quot;(.*?)&quot;</string>""").find(xml1) ?: Regex("""<string name="SSID">([^<]+)</string>""").find(xml1)
                val passMatch = Regex("""<string name="PreSharedKey">&quot;(.*?)&quot;</string>""").find(xml1)
                if (ssidMatch != null) ssid = ssidMatch.groupValues[1]
                if (passMatch != null) pass = passMatch.groupValues[1]
            }
            if (ssid.isEmpty() || pass.isEmpty()) {
                val xml2 = com.topjohnwu.superuser.Shell.cmd("cat /data/misc/wifi/softap.conf").exec().out.joinToString("\n")
                val ssidMatch = Regex("""ssid=([^\n]+)""").find(xml2)
                val passMatch = Regex("""wpa_passphrase=([^\n]+)""").find(xml2)
                if (ssidMatch != null) ssid = ssidMatch.groupValues[1]
                if (passMatch != null) pass = passMatch.groupValues[1]
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        if (ssid.isEmpty() || ssid == "<unknown ssid>") return null
        return Pair(ssid, pass)
    }

    private fun getIpAddress(type: String): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (intf in java.util.Collections.list(interfaces)) {
                val match = if (type == "tun") intf.name.contains("tun") else (!intf.name.contains("tun") && (intf.name.contains("wlan") || intf.name.contains("rmnet") || intf.name.contains("eth") || intf.name.contains("swlan") || intf.name.contains("ap")))
                if (match) {
                    for (addr in java.util.Collections.list(intf.inetAddresses)) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            return addr.hostAddress ?: ""
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return ""
    }
}
