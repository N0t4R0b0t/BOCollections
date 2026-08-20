package com.bocollections.backend.service;

/** Thrown by VisualScanService.callVisionModel when every configured Ollama endpoint failed. */
public class VisionUnavailableException extends RuntimeException {
    public VisionUnavailableException(String message) {
        super(message);
    }
}
