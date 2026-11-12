package io.github.yukiykchen.dsh.framework

/** Logcat filter: `DshStream`. */

object DshStreamLog {
    const val TAG = "DshStream"
    fun i(message: String) {
        DshDiagnostics.info(TAG, message)
    }
    fun question(message: String) {
        DshDiagnostics.info("DshQuestion", message)
    }
    fun preview(text: String, max: Int = 96): String {
        return DshDiagnostics.preview(text, max)
    }
    fun blocks(blocks: List<com.tencent.kuiklybase.streaming.MarkdownBlock>): String {
        return DshDiagnostics.blocks(blocks)
    }
    fun blockKind(content: String): String {
        return DshDiagnostics.blockKind(content)
    }
}
