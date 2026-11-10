package com.example.smartttexam.oj.http;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.security.interfaces.RSAPublicKey;
import java.util.Objects;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/** Separate audited issuer/key required. Does not accept shared legacy HMAC/test tokens.
 * Not a component: current SmartTT login does not issue this trusted token contract yet.
 */
public final class OjStrictJwtIdentity implements OjVerifiedIdentity {
    private final JWTVerifier verifier;
    public OjStrictJwtIdentity(RSAPublicKey key, String issuer, String audience) {
        if (issuer==null || issuer.trim().isEmpty() || audience==null || audience.trim().isEmpty())
            throw new IllegalArgumentException("Trusted issuer and audience required");
        if (Objects.requireNonNull(key).getModulus().bitLength()<2048)
            throw new IllegalArgumentException("RSA key must be at least 2048 bits");
        verifier=JWT.require(Algorithm.RSA256(key,null))
                .withIssuer(issuer).withAudience(audience).build();
    }
    @Override public String userId(HttpServletRequest request) {
        String header=request.getHeader("Authorization");
        if (header==null || !header.startsWith("Bearer ") || header.length()>16384)
            throw new ResponseStatusException(UNAUTHORIZED);
        try {
            DecodedJWT jwt=verifier.verify(header.substring(7));
            String id=jwt.getClaim("id").asString();
            long now=System.currentTimeMillis();
            if (jwt.getExpiresAt()==null || jwt.getIssuedAt()==null
                    || jwt.getExpiresAt().getTime()<=now || jwt.getIssuedAt().getTime()>now
                    || jwt.getExpiresAt().before(jwt.getIssuedAt())
                    || id==null || id.trim().isEmpty() || id.length()>128 || !id.equals(id.trim()))
                throw new IllegalArgumentException("Invalid identity claims");
            return id;
        } catch (RuntimeException error) { throw new ResponseStatusException(UNAUTHORIZED); }
    }
}
