package com.ballknowers.draftsim.recap;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * The structured shape Claude is asked to return. The section count and cite count are enforced
 * by {@code GroundingCheck} and the service, not by schema size constraints, which the
 * structured-output schema subset does not support (review F10).
 */
@JsonClassDescription("A short written recap of one fantasy league week")
public record RecapOutput(
        @JsonPropertyDescription("One-line headline for the week") String headline,
        @JsonPropertyDescription("The recap sections, in reading order") List<Section> sections) {

    public record Section(
            @JsonPropertyDescription("Short section title") String title,
            @JsonPropertyDescription("Section text; every number must come from the cited items") String body,
            @JsonPropertyDescription("JSON pointers into the league data that this section relies on, e.g. /matchups/3")
            List<String> cites) {}
}
