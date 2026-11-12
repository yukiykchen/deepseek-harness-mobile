package io.github.yukiykchen.dsh.adapter

import android.graphics.Typeface
import com.tencent.kuikly.core.render.android.adapter.IKRFontAdapter
import io.github.yukiykchen.dsh.KRApplication

object KRFontAdapter : IKRFontAdapter {

    override fun getTypeface(fontFamily: String, result: (Typeface?) -> Unit) {
        if (fontFamily.isEmpty()) {
            result(null)
        } else {
            var tfe: Typeface? = null
            when (fontFamily) {
                "Qvideo Digit" -> {
                    tfe = Typeface.createFromAsset(
                        KRApplication.application.assets,
                        "fonts/$fontFamily.ttf"
                    )
                }
            }
            result(tfe)
        }
    }
}