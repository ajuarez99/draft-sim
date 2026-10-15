package com.ballknowers.draftsim.ingest.espn;

/**
 * A field the translator requires was missing or the wrong type. Every ESPN shape in this build is
 * unverified until a real league has been read (research R9), so a wrong guess has to fail loudly
 * and name the field rather than write nulls. {@code fieldPath} is this code's own path
 * ({@code teams[].name}), never a value from the payload.
 */
public final class EspnShapeChanged extends EspnException {

    public static final String CODE = "ESPN_SHAPE_CHANGED";

    private final String fieldPath;

    public EspnShapeChanged(String fieldPath) {
        super(CODE, CODE + ": " + fieldPath);
        this.fieldPath = fieldPath;
    }

    public String fieldPath() {
        return fieldPath;
    }
}
