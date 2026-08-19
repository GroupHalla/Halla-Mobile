package com.halla.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IdentityBackupPolicyTest {
    private fun repositoryRoot(): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(current, "app/src/main/kotlin/com/halla/mobile/HallaCore.kt").isFile)
                return current
            current = current.parentFile ?: current
        }
        error("Repository root not found")
    }

    private fun source(relative: String) = File(repositoryRoot(), relative).readText()

    @Test
    fun portableBackupUsesPasswordEncryptionAndIntegrity() {
        val core = source("app/src/main/kotlin/com/halla/mobile/HallaCore.kt")
        assertTrue(core.contains("IDENTITY_BACKUP_ITERATIONS = 310_000"))
        assertTrue(core.contains("PBKDF2WithHmacSHA256"))
        assertTrue(core.contains("AES/GCM/NoPadding"))
        assertTrue(core.contains("GCMParameterSpec(128, iv)"))
        assertTrue(core.contains("cipher.updateAAD(backupAad"))
        assertTrue(core.contains("importedKeyAlgorithm(privateDer, publicDer)"))
        assertTrue(core.contains("declaredUid.isEmpty() || declaredUid == uid"))
        assertTrue(core.contains("privateDer.fill(0)"))
        assertTrue(core.contains("derived.fill(0)"))
        assertFalse(core.contains(".put(\"password\""))
    }

    @Test
    fun importRewrapsPrivateKeyWithNewAndroidKeystoreKey() {
        val core = source("app/src/main/kotlin/com/halla/mobile/HallaCore.kt")
        assertTrue(core.contains("encryptIdentityPrivateKey(privateDer)"))
        assertTrue(core.contains("\$alias.privateEncrypted"))
        assertTrue(core.contains("IDENTITY_MASTER_KEY_ALIAS"))
        val manifest = source("app/src/main/AndroidManifest.xml")
        // Não colocamos a chave privada em backup automático/cloud sem senha.
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
    }

    @Test
    fun identityManagerUsesStorageAccessFrameworkAndPromotesRestoredIdentity() {
        val activity = source("app/src/main/kotlin/com/halla/mobile/MainActivity.kt")
        assertTrue(activity.contains("ActivityResultContracts.CreateDocument"))
        assertTrue(activity.contains("ActivityResultContracts.OpenDocument"))
        assertTrue(activity.contains("HallaCore.exportIdentityBackup"))
        assertTrue(activity.contains("HallaCore.importIdentityBackup"))
        assertTrue(activity.contains("putString(\"client_uid\", result.alias)"))
        assertTrue(activity.contains("pendingIdentityBackupContent?.fill(0)"))
    }
}
