package io.github.yukiykchen.dsh.ui

import io.github.yukiykchen.dsh.model.DshToolCardType
import io.github.yukiykchen.dsh.model.DshRemoteToolKind
import io.github.yukiykchen.dsh.model.DshRemoteToolCallModel

fun DshToolCardType.iconAsset(): String = when (this) {
    DshToolCardType.TERMINAL -> "tool-terminal.svg"
    DshToolCardType.READ -> "tool-read.svg"
    DshToolCardType.DIFF -> "tool-diff.svg"
    DshToolCardType.SEARCH -> "tool-search.svg"
    DshToolCardType.WEB -> "tool-web.svg"
    DshToolCardType.JSON -> "tool-json.svg"
    DshToolCardType.GENERIC -> "tool-generic.svg"
}

/** Remote tool-name semantics choose the icon even before a result view exists. */

fun DshRemoteToolCallModel.iconAsset(): String = when (kind) {
    DshRemoteToolKind.BASH -> "tool-terminal.svg"
    DshRemoteToolKind.READ -> "tool-read.svg"
    DshRemoteToolKind.FILE_MUTATION -> "tool-diff.svg"
    DshRemoteToolKind.SEARCH -> "tool-search.svg"
    DshRemoteToolKind.WEB -> "tool-web.svg"
    DshRemoteToolKind.SKILL -> "tool-skill.svg"
    DshRemoteToolKind.ASK_QUESTION -> "tool-ask.svg"
    DshRemoteToolKind.TODO,
    DshRemoteToolKind.GENERIC -> cardType.iconAsset()
}
