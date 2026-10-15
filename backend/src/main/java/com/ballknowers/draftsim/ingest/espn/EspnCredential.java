package com.ballknowers.draftsim.ingest.espn;

import com.ballknowers.draftsim.security.CredentialCipher;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An ESPN login's two cookies ({@code espn_s2} and {@code SWID}). Spec 035 plan F14.
 *
 * <p>A final class, not a record: a record gets a free {@code toString} that prints every
 * component, which is exactly how a cookie ends up in a log line. Here:
 * <ul>
 *   <li>{@link #toString()} is {@code "[redacted]"} (plan F14 amended the task text);</li>
 *   <li>there is no public getter. {@link EspnClient} reads the values through package-private
 *       accessors; persistence goes through {@link #seal} / {@link #open}, so the store package never
 *       sees a plaintext value;</li>
 *   <li>Jackson can build one from a request body ({@code @JsonCreator}) but serializes it as the
 *       string {@code "[redacted]"}, so echoing a request object into a response or log through
 *       Jackson cannot reveal it.</li>
 * </ul>
 *
 * <p>Construction rejects blanks and any character that could break out of a {@code Cookie} header
 * (CR, LF, {@code ;}), with a fixed message that does not echo the value.
 */
@JsonSerialize(using = EspnCredential.Redacted.class)
public final class EspnCredential {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String espnS2;
    private final String swid;

    @JsonCreator
    public EspnCredential(@JsonProperty("espnS2") String espnS2, @JsonProperty("swid") String swid) {
        this.espnS2 = clean(espnS2);
        this.swid = clean(swid);
    }

    private static String clean(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("MALFORMED_CREDENTIAL");
        String t = v.trim();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\r' || c == '\n' || c == ';' || c < 0x20) throw new IllegalArgumentException("MALFORMED_CREDENTIAL");
        }
        return t;
    }

    /** The {@code Cookie} header value. Package-private on purpose: only {@link EspnClient} sends it. */
    String cookieHeader() {
        return "espn_s2=" + espnS2 + "; SWID=" + swid;
    }

    /** Encrypts both cookies as one JSON document; the store only ever sees the sealed bytes. */
    public CredentialCipher.Sealed seal(CredentialCipher cipher) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("espn_s2", espnS2);
        m.put("SWID", swid);
        try {
            return cipher.encrypt(JSON.writeValueAsBytes(m));
        } catch (IOException e) {
            throw new IllegalStateException("credential encoding failed");
        }
    }

    public static EspnCredential open(CredentialCipher cipher, CredentialCipher.Sealed sealed) {
        byte[] plain = cipher.decrypt(sealed);
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> m = JSON.readValue(new String(plain, StandardCharsets.UTF_8), Map.class);
            return new EspnCredential(m.get("espn_s2"), m.get("SWID"));
        } catch (IOException e) {
            throw new IllegalStateException("credential decoding failed");
        }
    }

    @Override
    public String toString() {
        return "[redacted]";
    }

    /** Writes the literal string "[redacted]" wherever Jackson is asked to write a credential. */
    public static final class Redacted extends JsonSerializer<EspnCredential> {
        @Override
        public void serialize(EspnCredential value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString("[redacted]");
        }
    }
}
