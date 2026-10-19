package me.nathanfallet.teamcode.cli.update

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.security.spec.X509EncodedKeySpec

internal actual fun verifyReleaseRsaPss(key: ReleasePublicKey, payload: ByteArray, signature: ByteArray): Boolean {
    if (key.spki.size !in 1..2048 || key.pkcs1.size !in 1..2048 || signature.size != 384) return false
    return try {
        val public = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(key.spki))
        Signature.getInstance("RSASSA-PSS").run {
            setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
            initVerify(public); update(payload); verify(signature)
        }
    } catch (_: GeneralSecurityException) { false }
    catch (_: IllegalArgumentException) { false }
}
