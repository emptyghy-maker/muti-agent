package com.ghy.mutiagent.controller;

import com.ghy.mutiagent.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/api/v1/health")
    public Result<Void> health() {
        return Result.ok();
    }
}
