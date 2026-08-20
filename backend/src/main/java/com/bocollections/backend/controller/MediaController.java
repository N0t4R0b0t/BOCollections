package com.bocollections.backend.controller;

import com.bocollections.backend.repository.ItemPhotoRepository;
import com.bocollections.backend.repository.ThriftSightingPhotoRepository;
import com.bocollections.backend.service.ScanSessionService;
import com.bocollections.backend.service.ThriftSessionService;
import com.bocollections.backend.service.storage.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/media")
@Tag(name = "Media", description = "Serves scan-session photos stored via the local filesystem storage backend")
@RequiredArgsConstructor
public class MediaController {

    private final StorageService storageService;
    private final ScanSessionService scanSessionService;
    private final ThriftSessionService thriftSessionService;
    private final ItemPhotoRepository itemPhotoRepository;
    private final ThriftSightingPhotoRepository thriftSightingPhotoRepository;

    private Long userId(Authentication auth) {
        return Long.parseLong(auth.getPrincipal().toString());
    }

    @GetMapping("/{key}")
    @Operation(summary = "Fetch a stored photo by key",
               description = "Scan-draft and thrift-sighting photos are only accessible to the user who owns " +
                             "that session; item photos belong to the shared catalogue (like the rest of an " +
                             "item's fields) and are visible to any authenticated user, same as GET /items/{id}.")
    public ResponseEntity<Resource> get(@PathVariable String key, Authentication auth) {
        if (itemPhotoRepository.existsByStorageKey(key)) {
            // shared catalogue data — no ownership check
        } else if (thriftSightingPhotoRepository.existsByStorageKey(key)) {
            thriftSessionService.assertSightingPhotoAccessible(key, userId(auth));
        } else {
            scanSessionService.assertPhotoAccessible(key, userId(auth));
        }
        Resource resource = storageService.load(key);
        MediaType mediaType = key.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(mediaType).body(resource);
    }
}
