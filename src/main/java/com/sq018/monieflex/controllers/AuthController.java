package com.sq018.monieflex.controllers;


import com.sq018.monieflex.configs.LogoutConfiguration;
import com.sq018.monieflex.dtos.LoginDto;
import com.sq018.monieflex.dtos.ResetPassword;
import com.sq018.monieflex.dtos.SignupDto;
import com.sq018.monieflex.enums.VerifyType;
import com.sq018.monieflex.payloads.ApiResponse;
import com.sq018.monieflex.payloads.LoginResponse;
import com.sq018.monieflex.services.AuthService;
import com.sq018.monieflex.services.implementations.AuthImplementation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@RequestBody LoginDto login) {
        return authService.login(login);
    }

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<String>> signup(@RequestBody @Validated SignupDto signup) {
        return authService.signup(signup);
    }

    @GetMapping("/confirm-email-address")
    public ResponseEntity<ApiResponse<String>> confirmEmailAddress(@RequestParam String token) {
        var response = authService.confirmEmail(token);
        return new ResponseEntity<>(response, response.getStatus());
    }

    @GetMapping("/resend-link")
    public ResponseEntity<ApiResponse<String>> resendLink(@RequestParam String email, @RequestParam VerifyType type) {
        return authService.resendLink(email, type);
    }

    @GetMapping("/check-email-for-password-reset")
    public ResponseEntity<ApiResponse<String>> checkEmailForPasswordReset(@RequestParam String emailAddress) {
        return authService.checkEmailForPasswordReset(emailAddress);
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<String>> resetPassword(
            @RequestParam String token, @RequestBody ResetPassword body
    ) {
        return authService.resetPassword(token, body.newPassword(), body.confirmPassword());
    }
}
