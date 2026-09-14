package com.pkb.settings;

import com.pkb.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> get() {
        return ApiResponse.ok(service.all());
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> put(@RequestBody Map<String, Object> body) {
        service.putAll(body);
        return ApiResponse.ok(service.all());
    }

    @PostMapping("/reset")
    public ApiResponse<Map<String, Object>> reset() {
        service.reset();
        return ApiResponse.ok(service.all());
    }
}
