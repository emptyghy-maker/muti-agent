package com.ghy.mutiagent.controller;

import com.ghy.mutiagent.common.Result;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.model.LoginRequest;
import com.ghy.mutiagent.model.LoginResponse;
import com.ghy.mutiagent.security.JwtUtil;
import com.ghy.mutiagent.security.UserAuth;
import com.ghy.mutiagent.security.UserAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserAuthService userAuthService;
    private final JwtUtil jwtUtil;

    public AuthController(UserAuthService userAuthService, JwtUtil jwtUtil) {
        this.userAuthService = userAuthService;
        this.jwtUtil = jwtUtil;
    }

    /** 登录：校验用户名密码，成功返回 JWT token */
    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest request) {
        Optional<UserAuth> userOpt = userAuthService.authenticate(request.getUsername(), request.getPassword());
        if (userOpt.isEmpty()) {
            log.warn("[AUTH] 登录失败：username={}", request.getUsername());
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        UserAuth user = userOpt.get();
        log.info("[AUTH] 登录成功：userId={} username={} role={}", user.id(), user.username(), user.role());
        LoginResponse resp = new LoginResponse();
        resp.setToken(jwtUtil.generate(user.id(), user.username(), user.role().name()));
        resp.setUserId(user.id());
        resp.setUsername(user.username());
        resp.setRole(user.role().name());
        return Result.ok(resp);
    }
}
