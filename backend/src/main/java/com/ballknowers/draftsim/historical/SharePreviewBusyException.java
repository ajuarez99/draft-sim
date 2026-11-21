package com.ballknowers.draftsim.historical;

/** Every render slot stayed taken for the whole wait (spec 079, PR-12): the controller answers 503. */
public class SharePreviewBusyException extends RuntimeException {
    public SharePreviewBusyException() {
        super("share preview renderer busy");
    }
}
