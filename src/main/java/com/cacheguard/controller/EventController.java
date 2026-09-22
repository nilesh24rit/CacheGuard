package com.cacheguard.controller;

import com.cacheguard.service.LoginEventService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Returns recent login activity for a user, read from their
 * per-user Redis Stream with XRANGE.
 */
@RestController
@RequestMapping("/api")
public class EventController {

    private final LoginEventService loginEventService;

    public EventController(LoginEventService loginEventService) {
        this.loginEventService = loginEventService;
    }

    /**
     * Recent login events for a user, newest first.
     *
     * @param username target user
     * @param limit    maximum number of events to return (defaults to 20)
     * @return JSON with the event list (timestamp, ip, deviceId, result)
     */
    @GetMapping("/events/{username}")
    public ResponseEntity<Map<String, Object>> getEvents(
            @PathVariable String username,
            @RequestParam(defaultValue = "20") int limit) {
        List<Map<String, Object>> events = loginEventService.getRecentEvents(username, limit);
        return ResponseEntity.ok(Map.of(
                "username", username,
                "count", events.size(),
                "events", events
        ));
    }
}
