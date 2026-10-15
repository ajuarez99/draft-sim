package com.ballknowers.draftsim.ingest.espn;

import com.ballknowers.draftsim.security.CredentialCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EspnCredentialTest {

    private static final String S2 = "SENTINEL_S2_abcdef0123456789";
    private static final String SWID = "{SENTINEL-SWID-0000}";

    private static CredentialCipher cipher() {
        byte[] raw = new byte[32];
        Arrays.fill(raw, (byte) 3);
        return new CredentialCipher(Base64.getEncoder().encodeToString(raw));
    }

    private static void assertNoSecret(String s) {
        assertFalse(s.contains("SENTINEL"), "leaked: " + s);
    }

    @Test
    void toStringAndStringValueOfNameNeitherSecret() {
        EspnCredential c = new EspnCredential(S2, SWID);
        assertEquals("[redacted]", c.toString());
        assertEquals("[redacted]", String.valueOf(c));
        assertNoSecret("" + c + " and " + Arrays.asList(c) + " and " + Map.of("k", c));
    }

    @Test
    void jacksonSerializationIsRedactedNotAbsent() throws Exception {
        ObjectMapper m = new ObjectMapper();
        String json = m.writeValueAsString(new EspnCredential(S2, SWID));
        assertEquals("\"[redacted]\"", json);
        assertNoSecret(m.writeValueAsString(Map.of("credential", new EspnCredential(S2, SWID))));
    }

    @Test
    void jacksonCanStillBuildOneFromARequestBody() throws Exception {
        EspnCredential c = new ObjectMapper().readValue("{\"espnS2\":\"" + S2 + "\",\"swid\":\"" + SWID + "\"}", EspnCredential.class);
        assertEquals("espn_s2=" + S2 + "; SWID=" + SWID, c.cookieHeader());
    }

    @Test
    void rejectsBlanksAndHeaderInjectionWithoutEchoingTheValue() {
        for (String bad : new String[] {null, "", "  ", "a;b", "a\nb", "a\rb"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new EspnCredential(bad, SWID));
            assertEquals("MALFORMED_CREDENTIAL", e.getMessage());
        }
    }

    @Test
    void sealAndOpenRoundTripWithoutThePlaintextInTheSealedBytes() {
        CredentialCipher cipher = cipher();
        CredentialCipher.Sealed sealed = new EspnCredential(S2, SWID).seal(cipher);
        assertFalse(new String(sealed.ciphertext(), java.nio.charset.StandardCharsets.ISO_8859_1).contains(S2));
        assertEquals("espn_s2=" + S2 + "; SWID=" + SWID, EspnCredential.open(cipher, sealed).cookieHeader());
    }
}
