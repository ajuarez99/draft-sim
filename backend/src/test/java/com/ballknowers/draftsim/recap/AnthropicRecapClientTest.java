package com.ballknowers.draftsim.recap;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No network (review F10). The real SDK request is built and serialized, and canned
 * {@code Message} JSON goes through the same {@code interpret} the live call uses.
 */
class AnthropicRecapClientTest {

    private static final String HAIKU = "claude-haiku-4-5";
    private static final String VALID_BODY =
            "{\"headline\":\"H\",\"sections\":[{\"title\":\"T\",\"body\":\"B\",\"cites\":[\"/matchups/0\"]}]}";

    private static JsonNode body(String model, boolean lowEffort) {
        StructuredMessageCreateParams<RecapOutput> p =
                AnthropicRecapClient.buildParams(model, 2048, "system text", "user turn", lowEffort);
        return AnthropicRecapClient.requestBody(p);
    }

    @Test
    void haikuRequestHasModelCapSchemaAndNoEffortOrThinking() {
        JsonNode b = body(HAIKU, false);
        assertEquals(HAIKU, b.get("model").asText());
        assertEquals(2048, b.get("max_tokens").asInt());
        assertEquals("system text", b.get("system").asText());
        JsonNode format = b.at("/output_config/format");
        assertFalse(format.isMissingNode(), "a structured-output schema must be present");
        assertNotNull(format.get("schema"));
        String schema = format.toString();
        assertFalse(schema.contains("minItems"), schema);
        assertFalse(schema.contains("maxItems"), schema);
        assertTrue(b.at("/output_config/effort").isMissingNode(), "Haiku must not be sent effort");
        assertTrue(b.get("thinking") == null, "no thinking is ever sent");
        // The client constructs with a dummy key and never prints it.
        assertEquals("AnthropicRecapClient", new AnthropicRecapClient("test-dummy", Duration.ofSeconds(5)).toString());
    }

    @Test
    void lowEffortIsSentOnlyWhenAsked() {
        JsonNode b = body("claude-sonnet-5-5", true);
        assertEquals("low", b.at("/output_config/effort").asText());
        assertFalse(b.at("/output_config/format").isMissingNode(), "effort must not displace the schema");
        assertTrue(b.get("thinking") == null);
    }

    private static Message message(String stopReason, String text) throws Exception {
        JsonNode json = ObjectMappers.jsonMapper().createObjectNode()
                .put("id", "msg_test").put("type", "message").put("role", "assistant")
                .put("model", HAIKU).put("stop_reason", stopReason);
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) json;
        root.putArray("content").addObject().put("type", "text").put("text", text);
        root.putNull("stop_sequence");
        root.putObject("usage").put("input_tokens", 1200).put("output_tokens", 340);
        return ObjectMappers.jsonMapper().treeToValue(root, Message.class);
    }

    @Test
    void refusalIsReportedWithoutParsing() throws Exception {
        // The text is not JSON: if interpret parsed it, parseError would be set.
        RecapCallResult r = AnthropicRecapClient.interpret(message("refusal", "I can't help with that."), 7);
        assertEquals("refusal", r.stopReason());
        assertNull(r.output());
        assertNull(r.parseError(), "the parser must never run on a refusal");
        assertEquals(1200, r.inputTokens());
        assertEquals(340, r.outputTokens());
        assertEquals(7, r.latencyMs());
    }

    @Test
    void maxTokensIsReportedWithoutParsing() throws Exception {
        RecapCallResult r = AnthropicRecapClient.interpret(message("max_tokens", "{\"headline\":\"cut off"), 1);
        assertEquals("max_tokens", r.stopReason());
        assertNull(r.output());
        assertNull(r.parseError());
    }

    @Test
    void endTurnWithValidBodyParses() throws Exception {
        RecapCallResult r = AnthropicRecapClient.interpret(message("end_turn", VALID_BODY), 1);
        assertEquals("end_turn", r.stopReason());
        assertNull(r.parseError());
        assertNotNull(r.output());
        assertEquals("H", r.output().headline());
        assertEquals(1, r.output().sections().size());
        assertEquals("/matchups/0", r.output().sections().get(0).cites().get(0));
    }

    @Test
    void endTurnWithInvalidJsonSetsParseError() throws Exception {
        RecapCallResult r = AnthropicRecapClient.interpret(message("end_turn", "this is not json"), 1);
        assertEquals("end_turn", r.stopReason());
        assertNull(r.output());
        assertNotNull(r.parseError());
    }
}
