package com.v2ray.ang.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityHevTunnelConfigBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream

class HevTunnelConfigActivity : BaseActivity() {
    private val binding by lazy { ActivityHevTunnelConfigBinding.inflate(layoutInflater) }
    private val configFile by lazy { File(filesDir, "hev-socks5-tunnel-custom.yaml") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = "HEV Tunnel Config")

        loadConfig()
    }

    private fun loadConfig() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (configFile.exists()) {
                    val content = FileInputStream(configFile).bufferedReader().use { it.readText() }
                    withContext(Dispatchers.Main) {
                        binding.etConfig.setText(content)
                        invalidateOptionsMenu()
                    }
                } else {
                    val defaultContent = """# Main configuration for hev-socks5-tunnel

tunnel:
  # Interface name (Di-comment biar diatur otomatis oleh VpnService Android)
# name: tun0
  # Interface MTU
  mtu: 8500
  # Multi-queue (WAJIB false di Android biar gak langsung mental/crash)
  multi-queue: false
  # IPv4 address
  ipv4: 198.18.0.1
  # IPv6 address
  ipv6: 'fc00::1'

# ==========================================
# DNS Upstream Anti-Iklan (AdGuard DNS)
# ==========================================
dns:
  # DNS server port
  port: 53
  # DNS server address (IPv4)
  address: 94.140.14.14

socks5:
  # Socks5 server port
  port: 1080
  # Socks5 server address (ipv4/ipv6)
  address: 127.0.0.1
  # Socks5 UDP relay mode (Tetap aktif untuk game/UDP)
  udp: 'udp'

misc:
  task-stack-size: 86016
  tcp-buffer-size: 65536
  # Buffer UDP tetap diperluas (Aman, gak bikin crash)
  udp-recv-buffer-size: 524288
  udp-copy-buffer-nums: 10
  max-session-count: 0
  connect-timeout: 10000
  tcp-read-write-timeout: 300000
  udp-read-write-timeout: 60000
  log-file: stderr
  log-level: warn"""
                    withContext(Dispatchers.Main) {
                        binding.etConfig.setText(defaultContent)
                        invalidateOptionsMenu()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveConfig() {
        val content = binding.etConfig.text.toString()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                FileOutputStream(configFile).bufferedWriter().use {
                    it.write(content)
                }
                withContext(Dispatchers.Main) {
                    toastSuccess(R.string.toast_success)
                    finish()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    toast(e.message ?: "Error saving config")
                }
            }
        }
    }

    private fun deleteConfig() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (configFile.exists()) {
                configFile.delete()
            }
            withContext(Dispatchers.Main) {
                toastSuccess(R.string.toast_success)
                finish()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.action_server, menu)
        menu.findItem(R.id.del_config)?.isVisible = configFile.exists()
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.save_config -> {
            saveConfig()
            true
        }
        R.id.del_config -> {
            deleteConfig()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
