package io.github.yukiykchen.dsh.relay

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class DshRelayPairLink(val origin: String, val pairId: String, val masterKey: String)

internal fun parseDshRelayPairQr(raw: String): DshRelayPairLink {
    val url = raw.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("二维码地址无效")
    require(url.username.isEmpty() && url.password.isEmpty()) { "二维码地址不能包含登录凭据" }
    val fragment = url.encodedFragment.orEmpty()
    val fragmentQuery = when {
        '?' in fragment -> fragment.substringAfter('?')
        '=' in fragment -> fragment
        else -> ""
    }
    val params = url.newBuilder().fragment(null)
        .encodedQuery(fragmentQuery.ifBlank { url.encodedQuery }).build()
    val id = params.queryParameterValues("id").singleOrNull()
    val key = params.queryParameterValues("key").singleOrNull()
    require(!id.isNullOrBlank() && !key.isNullOrBlank()) { "二维码缺少唯一的配对参数" }
    val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
    return DshRelayPairLink(origin, id, key)
}
