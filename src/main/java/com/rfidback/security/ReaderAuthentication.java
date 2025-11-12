package com.rfidback.security;

import java.util.Collections;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import com.rfidback.entity.ReaderEntity;

public class ReaderAuthentication extends AbstractAuthenticationToken {

    private final ReaderEntity reader;

    public ReaderAuthentication(ReaderEntity reader) {
        super(Collections.emptyList());
        this.reader = reader;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return reader.getApitoken();
    }

    @Override
    public Object getPrincipal() {
        return reader;
    }
}
