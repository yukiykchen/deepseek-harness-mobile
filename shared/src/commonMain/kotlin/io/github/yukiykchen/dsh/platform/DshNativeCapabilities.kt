package io.github.yukiykchen.dsh.platform

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.pager.PageData
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.views.*

val PageData.supportsRelayBridge: Boolean
    get() = isAndroid || isIOS || isOhOs

val PageData.supportsSshBridge: Boolean
    get() = isAndroid || isIOS
