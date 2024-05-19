package com.sq018.monieflex.dtos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record SignupDto(
        @NotBlank(message = "Email cannot be empty")
        @Email(message = "Email is not properly formatted")
        String emailAddress,
        @NotBlank(message = "BVN cannot be empty")
        @Size(min = 11, max = 11, message = "BVN cannot be less or more than 11")
        String bvn,
        @NotBlank(message = "First name cannot be empty")
        String firstName,
        @NotBlank(message = "Last name cannot be empty")
        String lastName,
        @NotBlank(message = "Phone number cannot be empty")
        @Size(min = 11, max = 11, message = "Phone number cannot be less or more than 11")
        String phoneNumber,
        @NotEmpty(message = "Password cannot be empty")
        String password

) {
}
