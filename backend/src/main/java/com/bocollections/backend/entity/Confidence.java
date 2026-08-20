package com.bocollections.backend.entity;

/** How sure the AI vision model is about an identification — shared vocabulary with ScanDraft's
 * (plain-String) confidence field, but this is the first place it's a real enum in this codebase. */
public enum Confidence {
    HIGH, MEDIUM, LOW
}
