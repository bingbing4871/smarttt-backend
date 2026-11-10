package com.example.smartttexam.oj.integration;

import com.example.smartttexam.oj.http.*;
import com.example.smartttexam.oj.judge.OjJudgeConfiguration;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.*;

/** TEST CLASSPATH ONLY. Explicit fixture rules, not a production login/eligibility implementation.
 * No component scan, application.properties, legacy login, Redis, MinIO or business startup.
 */
public final class OjIsolatedServer implements AutoCloseable {
    public static final String ISSUER="smarttt-isolated-lab", AUDIENCE="smarttt-oj";
    private final AnnotationConfigServletWebServerApplicationContext context;
    public final int port;

    public OjIsolatedServer(DataSource source, KeyPair keys, Path root, URI judge) throws Exception {
        this(source,keys,root,judge,16777216);
    }
    public OjIsolatedServer(DataSource source, KeyPair keys, Path root, URI judge,long artifactLimit) throws Exception {
        JdbcTemplate sql=new JdbcTemplate(source);
        if(!Collections.singletonList("SMARTTT_OJ_DISPOSABLE_LAB_V1").equals(
                sql.queryForList("SELECT marker FROM oj_lab_marker",String.class)))
            throw new IllegalArgumentException("Disposable lab marker required");
        Path dir=root.toAbsolutePath().normalize(); Files.createDirectories(dir);
        Path publicKey=dir.resolve("public.pem");
        Files.write(publicKey,("-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder().encodeToString(keys.getPublic().getEncoded())
                +"\n-----END PUBLIC KEY-----\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        OjJdbcAuthorizationData.Eligibility policy=new OjJdbcAuthorizationData.Eligibility() {
            public boolean student(String a,String b){return "LAB_ACTIVE".equals(a)&&"LAB_ACTIVE".equals(b);}
            public boolean classroom(String classroom,String term){
                // Fixture term dates, re-read on every authorization. Unknown/expired terms reject.
                return "lab-term".equals(term) && Integer.valueOf(1).equals(sql.queryForObject(
                    "SELECT count(1) FROM cm_term WHERE id=? AND iscurrentterm='1' AND begindate<=CURRENT_DATE AND enddate>=CURRENT_DATE",
                    Integer.class,term));
            }
        };
        OjTokenIssuer issuer=new OjTokenIssuer((login,password)->{
            List<String> ids=sql.query("SELECT u.id FROM st_users u JOIN sm_student s ON s.usersid=u.id "
                    +"WHERE BINARY u.loginname=BINARY ? AND BINARY u.pwd=BINARY ? AND u.catelog='1' "
                    +"AND u.status='LAB_ACTIVE' AND s.status='LAB_ACTIVE'",(rs,n)->rs.getString(1),login,new String(password));
            if(ids.size()!=1) throw new SecurityException("Login rejected");
            return ids.get(0);
        },(RSAPublicKey)keys.getPublic(),(RSAPrivateKey)keys.getPrivate(),ISSUER,AUDIENCE);
        context=new AnnotationConfigServletWebServerApplicationContext();
        Map<String,Object> properties=new HashMap<>();
        properties.put("oj.http.enabled","true");properties.put("oj.auth.enabled","true");
        properties.put("oj.judge.enabled",Boolean.toString(judge!=null));
        properties.put("oj.auth.public-key-file",publicKey.toString());properties.put("oj.auth.issuer",ISSUER);
        properties.put("oj.auth.audience",AUDIENCE);properties.put("oj.http.storage-directory",dir.resolve("submissions").toString());
        properties.put("oj.judge.result-directory",dir.resolve("results").toString());
        if(judge!=null) properties.put("oj.judge.endpoint",judge.toString());
        if(judge!=null) properties.put("oj.judge.compile-artifact-limit-bytes",Long.toString(artifactLimit));
        if(judge!=null) properties.put("oj.judge.bearer-token",System.getenv().getOrDefault("OJ_LAB_JUDGE_TOKEN",""));
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-lab",properties));
        context.registerBean(DataSource.class,()->source);
        context.registerBean(OjJdbcAuthorizationData.Eligibility.class,()->policy);
        context.registerBean(OjTokenIssuer.class,()->issuer);
        context.register(Web.class,OjAccessConfiguration.class,OjHttpConfiguration.class,OjHttpController.class,
                OjJudgeConfiguration.class,Login.class);
        try {context.refresh();port=context.getWebServer().getPort();}
        catch(RuntimeException|Error failure){context.close();throw failure;}
    }
    @Configuration @EnableWebMvc
    public static class Web implements WebMvcConfigurer {
        @Bean public TomcatServletWebServerFactory webServer() throws Exception {
            TomcatServletWebServerFactory f=new TomcatServletWebServerFactory(0);
            f.setAddress(InetAddress.getByName("127.0.0.1"));return f;
        }
        @Bean public DispatcherServlet dispatcherServlet(){return new DispatcherServlet();}
        @Bean public ServletRegistrationBean<DispatcherServlet> servlet(DispatcherServlet d){return new ServletRegistrationBean<>(d,"/");}
        @Override public void configurePathMatch(PathMatchConfigurer config){
            config.addPathPrefix("/api",type->type==OjHttpController.class);
        }
    }
    public static final class Credentials {
        public String login; public char[] password;
        @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("Invalid credentials");}
    }
    @RestController
    public static class Login {
        private final OjTokenIssuer issuer;
        public Login(OjTokenIssuer issuer){this.issuer=issuer;}
        @PostMapping("/api/exam/oj/login") public ResponseEntity<?> login(@RequestBody Credentials c){
            try {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Collections.singletonMap("token",issuer.login(c.login,c.password)));}
            catch(SecurityException e){return ResponseEntity.status(401).body(Collections.singletonMap("code","LOGIN_REJECTED"));}
            catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Collections.singletonMap("code","INVALID_INPUT"));}
            catch(RuntimeException e){return ResponseEntity.status(503).body(Collections.singletonMap("code","LOGIN_UNAVAILABLE"));}
            finally {if(c.password!=null)Arrays.fill(c.password,'\0');}
        }
        @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class})
        public ResponseEntity<?> invalid(){return ResponseEntity.badRequest().body(Collections.singletonMap("code","INVALID_INPUT"));}
    }
    public void close(){context.close();}
}
