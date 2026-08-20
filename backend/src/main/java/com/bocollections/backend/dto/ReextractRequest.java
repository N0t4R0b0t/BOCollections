package com.bocollections.backend.dto;

import lombok.Data;

/** Re-runs AI vision extraction against the stored photos already attached to a draft or item —
 * see ScanSessionService.reextractDraft / ItemService.reextract. Optional hint, same meaning as
 * ExtractRequest.hint. */
@Data
public class ReextractRequest {
    private String hint;
}
