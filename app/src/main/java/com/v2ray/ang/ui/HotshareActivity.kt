package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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

        binding.btnStartHotspot.setOnClickListener {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.setClassName("com.android.settings", "com.android.settings.TetherSettings")
            try {
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                } catch(e2: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }

        binding.btnStartRepeater.setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }

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
