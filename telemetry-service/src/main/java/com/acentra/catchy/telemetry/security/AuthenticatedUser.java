package com.acentra.catchy.telemetry.security;

/** A dashboard user identified by a verified bearer token. */
public record AuthenticatedUser(String username, Role role) {
    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
