package com.ballknowers.draftsim.recap;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredOutputConfig;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Optional;

/**
 * The real {@link RecapClient}. The API key is handed to the SDK builder and never kept in a
 * field of this class; {@link #toString()} is fixed so no log line can reach it (FR-008).
 * Request building ({@link #buildParams}) and response handling ({@link #interpret}) are split
 * out so tests can exercise both without a network (review F10).
 */
public class AnthropicRecapClient implements RecapClient {

    private final AnthropicClient sdk;

    AnthropicRecapClient(String apiKey, Duration timeout) {
        // Default retries (2) are kept: a transient 429/5xx is retried by the SDK before we see it.
        this.sdk = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(timeout).build();
    }

    @Override
    public RecapCallResult call(String model, int maxTokens, String systemPrompt, String userTurn,
                                boolean sendLowEffort) {
        StructuredMessageCreateParams<RecapOutput> params =
                buildParams(model, maxTokens, systemPrompt, userTurn, sendLowEffort);
        long started = System.nanoTime();
        Message raw;
        try {
            raw = sdk.messages().create(params).rawMessage();
        } catch (RateLimitException e) {
            throw new RecapUpstreamException(RecapUpstreamException.Kind.RATE_LIMITED_UPSTREAM,
                    "Anthropic API rate limited (status 429)");
        } catch (AnthropicServiceException e) {
            throw new RecapUpstreamException(RecapUpstreamException.Kind.API_ERROR,
                    "Anthropic API error (status " + e.statusCode() + ")");
        } catch (AnthropicException e) {
            // Transport/IO and retryable-after-retries errors. Only the exception type is named:
            // the message of a wrapped transport error is not ours to vouch for.
            throw new RecapUpstreamException(RecapUpstreamException.Kind.API_ERROR,
                    "Anthropic API call failed (" + e.getClass().getSimpleName() + ")");
        }
        return interpret(raw, (System.nanoTime() - started) / 1_000_000);
    }

    /**
     * The request, exactly as it will be sent. {@code effort} goes in only when asked for (never
     * for Haiku, R3) and no {@code thinking} is ever sent. No array-size constraint is in the
     * schema: the structured-output subset does not support them, so 3..5 sections is enforced in
     * Java (R3, F10).
     */
    static StructuredMessageCreateParams<RecapOutput> buildParams(String model, int maxTokens, String systemPrompt,
                                                                  String userTurn, boolean sendLowEffort) {
        StructuredOutputConfig.Builder<RecapOutput> config = StructuredOutputConfig.<RecapOutput>builder()
                .format(RecapOutput.class);
        if (sendLowEffort) config.effort(OutputConfig.Effort.LOW);
        return StructuredMessageCreateParams.<RecapOutput>builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(systemPrompt)
                .addUserMessage(userTurn)
                .outputConfig(config.build())
                .build();
    }

    /** The JSON request body as the SDK serializes it; used by the construction test and the schema hash. */
    static JsonNode requestBody(StructuredMessageCreateParams<RecapOutput> params) {
        return ObjectMappers.jsonMapper().valueToTree(params.rawParams()._body());
    }

    /**
     * Stop reason first, and only then a typed parse (F10): {@code .text()} deserializes, so on a
     * refusal or a truncated body it would throw rather than say why.
     */
    static RecapCallResult interpret(Message raw, long latencyMs) {
        Optional<StopReason> stop = raw.stopReason();
        String stopReason = stop.map(StopReason::asString).orElse(null);
        int in = (int) raw.usage().inputTokens();
        int out = (int) raw.usage().outputTokens();
        if (stop.isPresent() && !StopReason.END_TURN.equals(stop.get())) {
            // refusal, max_tokens and anything unexpected: no parse, the reason is the answer.
            return new RecapCallResult(stopReason, null, null, in, out, latencyMs);
        }
        try {
            StructuredMessage<RecapOutput> typed = new StructuredMessage<>(RecapOutput.class, raw);
            for (var block : typed.content()) {
                Optional<RecapOutput> text = block.text().map(t -> t.text());
                if (text.isPresent()) return new RecapCallResult(stopReason, text.get(), null, in, out, latencyMs);
            }
            return new RecapCallResult(stopReason, null, "no text block in the response", in, out, latencyMs);
        } catch (AnthropicInvalidDataException | IllegalArgumentException e) {
            return new RecapCallResult(stopReason, null, "response did not parse: " + e.getMessage(), in, out, latencyMs);
        }
    }

    @Override
    public String toString() {
        return "AnthropicRecapClient";
    }
}
