package io.github.yukiykchen.dsh.platform


fun dshWebSocketUrl(httpUrl: String): String = when {
    httpUrl.startsWith("https://") -> "wss://${httpUrl.removePrefix("https://")}"
    httpUrl.startsWith("http://") -> "ws://${httpUrl.removePrefix("http://")}"
    else -> httpUrl
}
