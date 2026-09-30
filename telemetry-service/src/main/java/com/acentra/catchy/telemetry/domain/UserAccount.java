package com.acentra.catchy.telemetry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Demo dashboard user. {@code passwordHash} is BCrypt, or null when password login is disabled for the user. */
@Entity
@Table(name = "user_account")
public class UserAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, length = 40)
    public String username;
    @Column(length = 100)
    public String passwordHash;
    @Column(nullable = false, length = 16)
    public String role;
}
