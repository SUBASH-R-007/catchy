package com.acentra.catchy.telemetry.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Dashboard roles, ordered by privilege: ADMIN includes ENGINEER includes VIEWER. */
public enum Role {
    VIEWER,
    ENGINEER,
    ADMIN;

    public boolean atLeast(Role other) {
        return ordinal() >= other.ordinal();
    }

    /** Authorities granted to a user of this role: the role itself plus every lower role. */
    public List<GrantedAuthority> authorities() {
        List<GrantedAuthority> out = new ArrayList<>();
        for (Role r : values()) {
            if (atLeast(r)) out.add(new SimpleGrantedAuthority("ROLE_" + r.name()));
        }
        return out;
    }
}
