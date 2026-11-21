package com.ballknowers.draftsim.historical;

/**
 * The share-preview drawing or its PNG encoding failed (spec 079, a headless or font problem). Its own type so the
 * controller answers 503 for this and nothing else: a data error from {@code publicGame} keeps its usual answer (CR-04).
 */
public class SharePreviewRenderException extends RuntimeException {
    public SharePreviewRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
