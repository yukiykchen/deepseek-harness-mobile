package io.github.yukiykchen.dsh.ui

import com.tencent.kuikly.core.reactive.collection.ObservableList
import io.github.yukiykchen.dsh.model.DshSkill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DshSkillCandidatesTest {
    @Test
    fun separateConversationsDoNotMutateEachOthersResults() {
        val first = visibleSkillList(skills(DshSkill("code", "first")), "code")
        visibleSkillList(skills(DshSkill("review", "second")), "review")
        assertEquals(listOf("code"), first.map { it.name })
    }

    @Test
    fun reusePreservesListIdentityButUpdatesSameNameMetadata() {
        val source = skills(DshSkill("code", "old"))
        val destination = ObservableList<DshSkill>()
        assertSame(destination, visibleSkillList(source, "", destination))
        source[0] = DshSkill("code", "new", modelInvocable = false)
        visibleSkillList(source, "", destination)
        assertEquals(source.toList(), destination.toList())
        visibleSkillList(source, "missing", destination)
        assertEquals(emptyList(), destination.toList())
    }

    private fun skills(vararg values: DshSkill) = ObservableList<DshSkill>().apply { addAll(values.toList()) }
}
