package com.bocollections.backend.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Temporary sink for client-side diagnostics that are otherwise hard to read on a phone (no easy
 * remote-debugging access). Just logs whatever it's given — remove once the camera/focus
 * investigation is done.
 */
@RestController
@RequestMapping("/debug")
@Slf4j
public class DebugController {

    @PostMapping("/log")
    public ResponseEntity<Void> log(@RequestBody Map<String, Object> body) {
        log.info("[client-debug] {}", body);
        return ResponseEntity.ok().build();
    }
}
