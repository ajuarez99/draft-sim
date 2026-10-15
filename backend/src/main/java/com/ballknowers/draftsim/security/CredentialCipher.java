package com.ballknowers.draftsim.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM over a small secret, keyed from {@code credentials.key} ({@code BK_CREDENTIAL_KEY}:
 * base64, must decode to exactly 32 bytes). Spec 035 plan F14 / research R13.
 *
 * <p>If the key is absent or malformed, startup logs ONE warning and {@link #isConfigured()} is
 * false. There is no plaintext fallback: {@link #encrypt} throws. The warning never contains the
 * key, only the fact.
 */
@Component
public class CredentialCipher {

    private static final Logger log = LoggerFactory.getLogger(CredentialCipher.class);

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    /** A nonce and the ciphertext (with GCM tag) it produced. Never print the arrays. */
    public record Sealed(byte[] nonce, byte[] ciphertext) {
        @Override
        public String toString() {
            return "Sealed[redacted]";
        }
    }

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(@Value("${credentials.key:}") String base64Key) {
        SecretKeySpec parsed = null;
        if (base64Key != null && !base64Key.isBlank()) {
            try {
                byte[] raw = Base64.getDecoder().decode(base64Key.trim());
                if (raw.length == 32) parsed = new SecretKeySpec(raw, "AES");
            } catch (IllegalArgumentException ignored) {
                // falls through to the warning below
            }
            if (parsed == null) {
                log.warn("BK_CREDENTIAL_KEY is set but is not base64 for exactly 32 bytes: private ESPN leagues are disabled");
            }
        } else {
            log.warn("BK_CREDENTIAL_KEY is not set: private ESPN leagues are disabled (public ESPN leagues still work)");
        }
        this.key = parsed;
    }

    public boolean isConfigured() {
        return key != null;
    }

    public Sealed encrypt(byte[] plaintext) {
        requireConfigured();
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new Sealed(nonce, c.doFinal(plaintext));
        } catch (GeneralSecurityException e) {
            // Message only; the exception itself carries nothing secret, but say nothing more.
            throw new IllegalStateException("credential encryption failed");
        }
    }

    public byte[] decrypt(Sealed sealed) {
        requireConfigured();
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed.nonce()));
            return c.doFinal(sealed.ciphertext());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("credential decryption failed");
        }
    }

    private void requireConfigured() {
        if (key == null) throw new IllegalStateException("CREDENTIALS_NOT_CONFIGURED");
    }
}
