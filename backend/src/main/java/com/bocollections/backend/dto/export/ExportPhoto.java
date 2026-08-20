package com.bocollections.backend.dto.export;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One photo, self-contained (base64-embedded bytes rather than a storage-key reference) so a
 * JSON export is a standalone file that survives being moved to a completely different backend
 * instance/storage backend and re-imported there. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportPhoto {
    private String angle;
    private Integer sortOrder;
    private String contentType;
    private String base64Data;
}
