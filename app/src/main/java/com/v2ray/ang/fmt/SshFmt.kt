package com.v2ray.ang.fmt

import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils
import java.net.URI

object SshFmt : FmtBase() {
    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.SSH)
        
        val uri = URI(Utils.fixIllegalUrl(str))
        if (uri.scheme != "ssh") return null

        config.remarks = Utils.urlDecode(uri.fragment.orEmpty()).ifEmpty { "none" }
        config.server = uri.host
        config.serverPort = uri.port.toString()
        
        uri.userInfo?.split(":", limit = 2)?.let { userInfo ->
            if (userInfo.size == 2) {
                config.username = Utils.urlDecode(userInfo[0])
                config.password = Utils.urlDecode(userInfo[1])
            }
        }
        
        val params = getQueryParam(uri)
        config.proxyHost = params["proxyHost"]
        config.proxyPort = params["proxyPort"]?.toIntOrNull()
        config.udpgwPort = params["udpgwPort"]?.toIntOrNull()
        config.payload = params["payload"]
        
        return config
    }
    
    fun toUri(config: ProfileItem): String {
        val userInfo = if (!config.username.isNullOrEmpty() && !config.password.isNullOrEmpty()) {
            "${Utils.urlEncode(config.username.orEmpty())}:${Utils.urlEncode(config.password.orEmpty())}"
        } else {
            ""
        }
        
        val params = mutableMapOf<String, String>()
        if (!config.proxyHost.isNullOrEmpty()) params["proxyHost"] = config.proxyHost!!
        if (config.proxyPort != null && config.proxyPort!! > 0) params["proxyPort"] = config.proxyPort.toString()
        if (config.udpgwPort != null && config.udpgwPort!! > 0) params["udpgwPort"] = config.udpgwPort.toString()
        if (!config.payload.isNullOrEmpty()) params["payload"] = config.payload!!
        
        val query = params.map { "${it.key}=${Utils.urlEncode(it.value)}" }.joinToString("&")
        
        return "ssh://$userInfo@${config.server}:${config.serverPort}/?$query#${Utils.urlEncode(config.remarks)}"
    }
    
    fun toOutbound(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = OutboundBean(protocol = EConfigType.SSH.name.lowercase())
        outboundBean.tag = EConfigType.SSH.name.lowercase()
        
        val serverBean = OutboundBean.OutSettingsBean.ServersBean()
        serverBean.address = profileItem.server ?: ""
        serverBean.port = profileItem.serverPort?.toIntOrNull() ?: 22
        serverBean.user = profileItem.username
        serverBean.password = profileItem.password
        
        serverBean.proxyHost = profileItem.proxyHost
        serverBean.proxyPort = profileItem.proxyPort
        serverBean.payload = profileItem.payload
        serverBean.udpgwPort = profileItem.udpgwPort
        
        val settingsBean = OutboundBean.OutSettingsBean()
        settingsBean.servers = listOf(serverBean)
        outboundBean.settings = settingsBean
        
        return outboundBean
    }
}