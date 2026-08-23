package com.halla.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChannelAdministrationPolicyTest {
    private fun root(): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(current, "app/src/main/kotlin/com/halla/mobile/MainActivity.kt").isFile)
                return current
            current = current.parentFile ?: current
        }
        error("Repository root not found")
    }

    private fun activity(): String =
        File(root(), "app/src/main/kotlin/com/halla/mobile/MainActivity.kt").readText()

    @Test
    fun fullChannelCreationOffersAllTypesBehindPermissions() {
        val source = activity()
        assertTrue(source.contains("hasPermission(\"chanCreateSemi\")"))
        assertTrue(source.contains("hasPermission(\"chanCreatePerm\")"))
        // o tipo enviado de fato acompanha o radio selecionado
        val createBlock = source.substringAfter("private fun showCreateChannelDialog")
            .substringBefore("private fun deleteChannel")
        assertTrue(createBlock.contains("typeSemi.id -> 1"))
        assertTrue(createBlock.contains("typePerm.id -> 2"))
        assertTrue(createBlock.contains("put(\"topic\", inputTopic.text.toString())"))
        assertTrue(createBlock.contains("put(\"codec\", 4 + codecSpinner.selectedItemPosition)"))
    }

    @Test
    fun deleteMoveAndChannelPermissionsRequireGlobalPermissions() {
        val source = activity()
        val optionsBlock = source.substringAfter("private fun showChannelOptionsDialog")
            .substringBefore("private fun joinChannelWithPassword")
        // excluir exige chanDelete; mover e permissões por canal exigem chanEdit
        assertTrue(optionsBlock.contains("hasPermission(\"chanDelete\")"))
        assertTrue(optionsBlock.contains("deleteChannel(chanId, chanName)"))
        assertTrue(optionsBlock.contains("hasPermission(\"chanEdit\")"))
        assertTrue(optionsBlock.contains("moveChannel(chanId)"))
        assertTrue(optionsBlock.contains("showChannelPermissionsDialog(chanId, chanName)"))
        // confirmação obrigatória antes do chan_delete
        val deleteBlock = source.substringAfter("private fun deleteChannel")
            .substringBefore("private fun moveChannel")
        assertTrue(deleteBlock.contains("R.string.channel_delete_confirm"))
        assertTrue(deleteBlock.contains("chan_delete"))
    }

    @Test
    fun moveDialogNeverOffersSelfOrDescendants() {
        val source = activity()
        assertTrue(source.contains("isDescendantOf(id, chanId)"))
        val moveBlock = source.substringAfter("private fun moveChannel")
            .substringBefore("private fun isDescendantOf")
        assertTrue(moveBlock.contains("if (id == chanId) continue"))
        assertTrue(moveBlock.contains("chan_move"))
    }

    @Test
    fun channelPermissionsEditorPreservesUntouchedGroups() {
        val source = activity()
        val permBlock = source.substringAfter("private fun showGroupChannelPermEditor")
            .substringBefore("private fun showUserOptionsDialog")
        // baseia-se no groupPerms atual do canal e troca apenas o cargo editado
        assertTrue(permBlock.contains("channel.optJSONObject(\"groupPerms\")"))
        assertTrue(permBlock.contains("permsOut.put(groupId.toString(), groupPerms)"))
        assertFalse(permBlock.contains("groupPerms.put(key, -1)"))
    }
}
