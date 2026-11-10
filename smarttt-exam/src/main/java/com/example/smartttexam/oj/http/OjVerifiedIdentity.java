package com.example.smartttexam.oj.http;

import javax.servlet.http.HttpServletRequest;

/** Must return a verified st_users.id; no production bean is registered until issuer audit completes. */
public interface OjVerifiedIdentity {
    String userId(HttpServletRequest request);
}
