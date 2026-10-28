package com.employeemonitor.api.auth;

import com.employeemonitor.api.auth.AuthDtos.ChangePasswordRequest;
import com.employeemonitor.api.auth.AuthDtos.LoginRequest;
import com.employeemonitor.api.auth.AuthDtos.RefreshRequest;
import com.employeemonitor.api.auth.AuthDtos.TokenResponse;
import com.employeemonitor.api.auth.AuthDtos.UserInfo;
import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.security.CurrentUser;
import com.employeemonitor.api.users.UserRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;
    private final UserRepository users;

    public AuthController(AuthService authService, UserRepository users) {
        this.authService = authService;
        this.users = users;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.username(), request.password());
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }

    @GetMapping("/me")
    public UserInfo me() {
        Long id = CurrentUser.require().id();
        return users.findById(id).map(UserInfo::from).orElseThrow(() -> ApiException.notFound("User", id));
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(CurrentUser.require().id(), request.currentPassword(), request.newPassword());
    }
}
