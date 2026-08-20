package com.bocollections.backend.config;

/** Which vision call is being made — lets each Ollama endpoint pull a different model per task. */
public enum VisionTask {
    VERIFY, EXTRACT, SHELF
}
