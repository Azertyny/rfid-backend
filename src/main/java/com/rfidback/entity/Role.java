package com.rfidback.entity;

public enum Role {
    ADMINISTRATEUR,
    OPERATEUR;

    public String authority() {
        return "ROLE_" + name();
    }
}
