package io.github.yukiykchen.dsh.adapter

import android.util.Log
import com.tencent.kuikly.core.render.android.adapter.IKRLogAdapter
import io.github.yukiykchen.dsh.BuildConfig

object KRLogAdapter : IKRLogAdapter {

    override val asyncLogEnable: Boolean
        get() = true

    override fun i(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.i(tag, msg)
    }

    override fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
    }

    override fun e(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.e(tag, msg)
    }
}
