package com.example.dsh.adapter

import android.app.Activity
import android.content.Context
import com.tencent.kuikly.core.render.android.adapter.IKRRouterAdapter
import com.example.dsh.KuiklyRenderActivity
import org.json.JSONObject

object KRRouterAdapter : IKRRouterAdapter {

    override fun openPage(
        context: Context,
        pageName: String,
        pageData: JSONObject,
    ) {
        KuiklyRenderActivity.start(context, pageName, pageData)
        if (pageData.optBoolean("replaceCurrentPage", false)) (context as? Activity)?.finish()
    }

    override fun closePage(context: Context) {
        (context as? Activity)?.finish()
    }
}