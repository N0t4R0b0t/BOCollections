package com.bocollections.backend.config;

/** Which backend a vision endpoint talks to — see {@link VisionProperties.Endpoint}. */
public enum VisionProvider {
    OLLAMA,
    GEMINI
}
