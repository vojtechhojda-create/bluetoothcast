package app.proxylink

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

object keys {
    private const val alias = "proxylink-device"

    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensure(ks: KeyStore) {
        if (ks.containsAlias(alias)) return
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        gen.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        gen.generateKeyPair()
    }

    @Synchronized
    fun publickey(): String {
        val ks = store()
        ensure(ks)
        return Base64.encodeToString(ks.getCertificate(alias).publicKey.encoded, Base64.NO_WRAP)
    }

    @Synchronized
    fun sign(data: ByteArray): String {
        val ks = store()
        ensure(ks)
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(ks.getKey(alias, null) as PrivateKey)
        s.update(data)
        return Base64.encodeToString(s.sign(), Base64.NO_WRAP)
    }
}
