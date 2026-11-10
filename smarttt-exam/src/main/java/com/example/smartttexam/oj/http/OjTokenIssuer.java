package com.example.smartttexam.oj.http;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.security.interfaces.*;
import java.util.*;

/** No HTTP mapping or automatic Bean. Credential proof must be supplied by an audited login service. */
public final class OjTokenIssuer {
    public interface CredentialProof {
        /** Return database user ID only after checking credentials and current eligibility; otherwise reject. */
        String authenticate(String login,char[] password);
    }
    private final CredentialProof proof;
    private final Algorithm algorithm;
    private final String issuer,audience;
    public OjTokenIssuer(CredentialProof proof,RSAPublicKey publicKey,RSAPrivateKey privateKey,String issuer,String audience) {
        if(publicKey==null || privateKey==null || publicKey.getModulus().bitLength()<2048
                || !publicKey.getModulus().equals(privateKey.getModulus()) || issuer==null || issuer.trim().isEmpty()
                || audience==null || audience.trim().isEmpty())throw new IllegalArgumentException("Invalid issuer contract");
        this.proof=Objects.requireNonNull(proof);this.algorithm=Algorithm.RSA256(publicKey,privateKey);this.issuer=issuer;this.audience=audience;
    }
    public String login(String login,char[] password) {
        if(login==null || login.trim().isEmpty() || login.length()>128 || password==null || password.length<1 || password.length>1024)throw new IllegalArgumentException("Invalid login");
        try {
            String id=proof.authenticate(login,password);
            if(id==null || id.trim().isEmpty() || !id.equals(id.trim()) || id.length()>128)throw new SecurityException("Login rejected");
            long now=System.currentTimeMillis();
            return JWT.create().withIssuer(issuer).withAudience(audience).withClaim("id",id)
                    .withIssuedAt(new Date(now)).withExpiresAt(new Date(now+900000)).sign(algorithm);
        } finally {Arrays.fill(password,'\0');}
    }
}
