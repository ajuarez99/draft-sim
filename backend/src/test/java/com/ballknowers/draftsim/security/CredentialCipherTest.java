package com.ballknowers.draftsim.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialCipherTest {

    private static String key(int bytes) {
        byte[] raw = new byte[bytes];
        Arrays.fill(raw, (byte) 7);
        return Base64.getEncoder().encodeToString(raw);
    }

    @Test
    void roundTrips() {
        CredentialCipher c = new CredentialCipher(key(32));
        byte[] plain = "{\"espn_s2\":\"abc\",\"SWID\":\"{x}\"}".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(plain, c.decrypt(c.encrypt(plain)));
    }

    @Test
    void twoEncryptionsOfTheSamePlaintextDiffer() {
        CredentialCipher c = new CredentialCipher(key(32));
        byte[] plain = "same".getBytes(StandardCharsets.UTF_8);
        CredentialCipher.Sealed a = c.encrypt(plain);
        CredentialCipher.Sealed b = c.encrypt(plain);
        assertFalse(Arrays.equals(a.nonce(), b.nonce()));
        assertFalse(Arrays.equals(a.ciphertext(), b.ciphertext()));
    }

    @Test
    void theCiphertextDoesNotContainThePlaintext() {
        CredentialCipher c = new CredentialCipher(key(32));
        String secret = "SENTINEL_COOKIE_VALUE_1234567890";
        byte[] sealed = c.encrypt(secret.getBytes(StandardCharsets.UTF_8)).ciphertext();
        assertFalse(new String(sealed, StandardCharsets.ISO_8859_1).contains(secret));
        assertEquals(secret.length() + 16, sealed.length, "GCM adds exactly a 16-byte tag");
    }

    @Test
    void aTamperedCiphertextIsRejected() {
        CredentialCipher c = new CredentialCipher(key(32));
        CredentialCipher.Sealed s = c.encrypt("hello".getBytes(StandardCharsets.UTF_8));
        s.ciphertext()[0] ^= 1;
        assertThrows(IllegalStateException.class, () -> c.decrypt(s));
    }

    @Test
    void noKeyMeansNotConfiguredAndNoPlaintextFallback() {
        for (String bad : new String[] {"", null, "not base64 !!", key(16)}) {
            CredentialCipher c = new CredentialCipher(bad);
            assertFalse(c.isConfigured(), "key=" + bad);
            assertThrows(IllegalStateException.class, () -> c.encrypt(new byte[] {1}));
        }
        assertTrue(new CredentialCipher(key(32)).isConfigured());
    }

    @Test
    void sealedToStringHidesTheBytes() {
        CredentialCipher.Sealed s = new CredentialCipher(key(32)).encrypt(new byte[] {1, 2, 3});
        assertNotEquals(-1, s.toString().indexOf("redacted"));
    }
}
