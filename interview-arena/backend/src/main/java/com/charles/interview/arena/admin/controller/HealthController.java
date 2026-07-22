package com.charles.interview.arena.admin.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.charles.interview.arena.common.BaseResponse;
import com.charles.interview.arena.common.ResultUtils;

@RestController
public class HealthController {

    @GetMapping("/api/health")
    public BaseResponse<Map<String,String>> health() {
        Map<String, String> result = new HashMap<>();
        result.put("status", "ok");
        result.put("service", "interview-arena");
        return ResultUtils.success(result);
    }
}