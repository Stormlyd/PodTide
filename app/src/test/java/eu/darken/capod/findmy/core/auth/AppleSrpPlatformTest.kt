package eu.darken.capod.findmy.core.auth

import android.app.Application
import android.os.Build
import eu.darken.capod.findmy.core.auth.SrpVectors.bytes
import eu.darken.capod.findmy.core.auth.SrpVectors.client
import eu.darken.capod.findmy.core.auth.SrpVectors.proof
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest
import javax.crypto.Mac

/** Cached Android surface smoke. Robolectric's host JCA providers do not certify real-device providers. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], application = Application::class)
class AppleSrpPlatformTest {
    @Test
    fun referenceProofsOnCachedAndroidSurface() {
        assertEquals(33, Build.VERSION.SDK_INT)
        println("SRP smoke SDK=${Build.VERSION.SDK_INT}; SHA256 provider=${MessageDigest.getInstance("SHA-256").provider.name}; HMAC provider=${Mac.getInstance("HmacSHA256").provider.name}")
        SrpVectors.all.forEach { vector ->
            vector.client().use { client ->
                assertArrayEquals(vector.bytes("public_A_hex"), client.publicA)
                vector.proof(client).use {
                    assertArrayEquals(vector.bytes("M1_hex"), it.m1)
                    assertArrayEquals(vector.bytes("M2_hex"), it.m2)
                }
            }
        }
    }
}
