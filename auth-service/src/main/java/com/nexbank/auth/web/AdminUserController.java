package com.nexbank.auth.web;

import com.nexbank.auth.dto.AuthDtos.RoleChangeRequest;
import com.nexbank.auth.dto.AuthDtos.StaffUserRequest;
import com.nexbank.auth.dto.AuthDtos.UserView;
import com.nexbank.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {
    private final AuthService auth;
    public AdminUserController(AuthService auth) { this.auth = auth; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@RequestHeader("X-User-Id") String actor,
                           @RequestHeader(value="X-User-Role", defaultValue="") String role,
                           @Valid @RequestBody StaffUserRequest request) {
        requireAdmin(role);
        if (request.role() == com.nexbank.auth.entity.UserRole.CUSTOMER)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use public signup for customer accounts");
        return auth.createStaff(actor, request);
    }

    @PutMapping("/{id}/role")
    public UserView changeRole(@PathVariable UUID id,
                               @RequestHeader("X-User-Id") String actor,
                               @RequestHeader(value="X-User-Role", defaultValue="") String role,
                               @Valid @RequestBody RoleChangeRequest request) {
        requireAdmin(role);
        return auth.changeRole(actor, id, request.role());
    }

    private void requireAdmin(String role) {
        if (!role.equals("ADMIN")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN role required");
    }
}
