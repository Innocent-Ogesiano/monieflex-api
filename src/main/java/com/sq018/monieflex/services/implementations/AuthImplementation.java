package com.sq018.monieflex.services.implementations;

import com.sq018.monieflex.dtos.LoginDto;
import com.sq018.monieflex.dtos.SignupDto;
import com.sq018.monieflex.entities.User;
import com.sq018.monieflex.enums.AccountStatus;
import com.sq018.monieflex.enums.VerifyType;
import com.sq018.monieflex.exceptions.MonieFlexException;
import com.sq018.monieflex.payloads.ApiResponse;
import com.sq018.monieflex.payloads.LoginResponse;
import com.sq018.monieflex.repositories.UserRepository;
import com.sq018.monieflex.repositories.WalletRepository;
import com.sq018.monieflex.services.AuthService;
import com.sq018.monieflex.services.WalletService;
import com.sq018.monieflex.utils.ForgotPasswordEmailTemplate;
import com.sq018.monieflex.utils.SignupEmailTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthImplementation implements AuthService {
    private final UserRepository userRepository;
    private final AuthenticationManager authenticationManager;
    private final JwtImplementation jwtImplementation;
    private final WalletService walletService;
    private final WalletRepository walletRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailImplementation emailImplementation;

    private final Long expire = 900000L;

    protected String generateToken(User user, Long expiryDate) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("first_name", user.getFirstName());
        claims.put("last_name", user.getLastName());
        return jwtImplementation.generateJwtToken(claims, user.getEmailAddress(), expiryDate);
    }

    @Override
    public ResponseEntity<ApiResponse<LoginResponse>> login(LoginDto loginDto){
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginDto.emailAddress(), loginDto.password())
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        var user = userRepository.findByEmailAddress(loginDto.emailAddress())
                .orElseThrow(()-> new UsernameNotFoundException("User not found"));

        LoginResponse loginResponse = new LoginResponse(
                user.getFirstName(),
                user.getLastName(),
                user.getEmailAddress(),
                generateToken(user, null)
        );
        ApiResponse<LoginResponse> response = new ApiResponse<>(loginResponse, "Login successful");
        return new ResponseEntity<>(response, response.getStatus());
    }

    @Override
    public ResponseEntity<ApiResponse<String>> signup(SignupDto signupDto) {
        var user = userRepository.findByEmailAddress(signupDto.emailAddress());
        if(user.isPresent()) {
            throw new MonieFlexException("Email address already exists");
        } else {
            User newUser = new User();
            newUser.setEmailAddress(signupDto.emailAddress());
            newUser.setEncryptedPassword(passwordEncoder.encode(signupDto.password()));
            newUser.setFirstName(signupDto.firstName());
            newUser.setLastName(signupDto.lastName());
            newUser.setBvn(signupDto.bvn());
            newUser.setPhoneNumber(signupDto.phoneNumber());
            newUser.setStatus(AccountStatus.SUSPENDED);

            var wallet = walletService.create(newUser);

            userRepository.save(newUser);
            walletRepository.save(wallet);

            emailImplementation.sendEmail(
                    SignupEmailTemplate.signup(
                            newUser.getFirstName(),
                            generateToken(newUser, expire)
                    ),
                    "Verify your email address",
                    newUser.getEmailAddress()
            );

            ApiResponse<String> response = new ApiResponse<>(
                    "Check your email for OTP verification",
                    "Successfully created account"
            );
            return new ResponseEntity<>(response, response.getStatus());
        }
    }

    @Override
    public ApiResponse<String> confirmEmail(String token) {
        String email = jwtImplementation.extractEmailAddressFromToken(token);
        if(email != null) {
            if(jwtImplementation.isExpired(token)) {
                throw new MonieFlexException("Link has expired. Please request for a new link.") ;
            } else {
                var user = userRepository.findByEmailAddress(email);
                if(user.isPresent()) {
                    if(!user.get().isEnabled()) {
                        var update = user.get();
                        update.setStatus(AccountStatus.ACTIVE);
                        userRepository.save(update);
                        return new ApiResponse<>(
                                "Your email address is now verified. Please login.",
                                HttpStatus.OK
                        );
                    } else {
                        throw new MonieFlexException("Email address is already verified.") ;
                    }
                } else {
                    throw new MonieFlexException("User not found. Please check the link.");
                }
            }
        } else {
            throw new MonieFlexException("Link is not properly formatted.");
        }
    }

    @Override
    public ResponseEntity<ApiResponse<String>> resendLink(String email, VerifyType type) {
        var user = userRepository.findByEmailAddress(email);
        if(user.isPresent()) {
            if(type == VerifyType.SIGNUP && user.get().isEnabled()) {
                throw new MonieFlexException("This email is already verified");
            } else {
                if(type == VerifyType.SIGNUP && !user.get().isEnabled()) {
                    emailImplementation.sendEmail(
                            SignupEmailTemplate.signup(
                                    user.get().getFirstName(),
                                    generateToken(user.get(), expire)
                            ),
                            "Verify your email address",
                            user.get().getEmailAddress()
                    );
                } else {
                    emailImplementation.sendEmail(
                            ForgotPasswordEmailTemplate.password(
                                    user.get().getFirstName(),
                                    generateToken(user.get(),expire)
                            ),
                            "Password Reset - Verify your email address",
                            user.get().getEmailAddress()
                    );
                }
                ApiResponse<String> response = new ApiResponse<>(
                        "Check your email for verification",
                        "Success in sending link"
                );
                return new ResponseEntity<>(response, response.getStatus());
            }
        } else {
            throw new MonieFlexException("User does not exist");
        }
    }

    @Override
    public ResponseEntity<ApiResponse<String>> checkEmailForPasswordReset(String emailAddress) {
        var user = userRepository.findByEmailAddress(emailAddress);
        if (user.isPresent()) {
            emailImplementation.sendEmail(
                    ForgotPasswordEmailTemplate.password(
                            user.get().getFirstName(),
                            generateToken(user.get(),expire)
                    ),
                    "Password Reset - Verify your email address",
                    user.get().getEmailAddress()
            );
            user.get().setPasswordRecovery(true);
            userRepository.save(user.get());
            ApiResponse<String> response = new ApiResponse<>(
                    "Check your email for the password reset link",
                    "Success"
            );
            return new ResponseEntity<>(response, response.getStatus());
        } else {
            throw new MonieFlexException("Email not found");
        }
    }

    @Override
    public ResponseEntity<ApiResponse<String>> resetPassword(
            String token, String password, String confirmPassword
    ) {
        String email = jwtImplementation.extractEmailAddressFromToken(token);
        if(email != null) {
            if(jwtImplementation.isExpired(token)) {
                throw new MonieFlexException("Link has expired. Please request for a new link.");
            } else {
                var user = userRepository.findByEmailAddress(email);
                if(user.isPresent() && user.get().getPasswordRecovery()) {
                    if(password.equals(confirmPassword)) {
                        user.get().setEncryptedPassword(passwordEncoder.encode(password));
                        user.get().setPasswordRecovery(false);
                        userRepository.save(user.get());
                        ApiResponse<String> response = new ApiResponse<>(
                                "Password changed for %s".formatted(user.get().getEmailAddress()),
                                "Successfully changed password"
                        );
                        return new ResponseEntity<>(response, response.getStatus());
                    } else {
                        throw new MonieFlexException("Password does not match");
                    }
                } else {
                    throw new MonieFlexException("User not found");
                }
            }
        } else {
            throw new MonieFlexException("Link is not properly formatted.");
        }
    }
}