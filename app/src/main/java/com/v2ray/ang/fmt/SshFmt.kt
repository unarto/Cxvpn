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
        config.proxyHost?.let { if (it.isNotEmpty()) params["proxyHost"] = it }
        config.proxyPort?.let { if (it > 0) params["proxyPort"] = it.toString() }
        config.udpgwPort?.let { if (it > 0) params["udpgwPort"] = it.toString() }