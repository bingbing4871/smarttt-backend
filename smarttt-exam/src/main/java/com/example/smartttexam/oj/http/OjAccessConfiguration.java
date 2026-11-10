package com.example.smartttexam.oj.http;

import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
@ConditionalOnProperty(name="oj.auth.enabled",havingValue="true",matchIfMissing=false)
public class OjAccessConfiguration {
    @Bean public OjVerifiedIdentity ojVerifiedIdentity(
            @org.springframework.beans.factory.annotation.Value("${oj.auth.public-key-file}") String file,
            @org.springframework.beans.factory.annotation.Value("${oj.auth.issuer}") String issuer,
            @org.springframework.beans.factory.annotation.Value("${oj.auth.audience}") String audience) throws Exception {
        java.nio.file.Path path=java.nio.file.Paths.get(file);
        if(!path.isAbsolute() || !java.nio.file.Files.isRegularFile(path,java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || java.nio.file.Files.size(path)>16384 || !path.normalize().equals(path.toRealPath()))throw new IllegalArgumentException("Invalid public key file");
        String pem=new String(java.nio.file.Files.readAllBytes(path),java.nio.charset.StandardCharsets.US_ASCII);
        byte[] bytes=java.util.Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----",""));
        java.security.interfaces.RSAPublicKey key=(java.security.interfaces.RSAPublicKey)java.security.KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(bytes));
        return new OjStrictJwtIdentity(key,issuer,audience);
    }
    @Bean public OjAuthorizationData ojAuthorizationData(DataSource source,OjJdbcAuthorizationData.Eligibility policy) {
        return new OjJdbcAuthorizationData(source,policy);
    }
    @Bean public OjHttpAccess ojProductionAccess(OjVerifiedIdentity identity,OjAuthorizationData data){return new OjProductionAccess(identity,data);}
}
