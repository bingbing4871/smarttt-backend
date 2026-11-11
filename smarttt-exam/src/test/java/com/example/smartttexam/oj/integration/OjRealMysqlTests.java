package com.example.smartttexam.oj.integration;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.example.smartttexam.oj.http.*;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: fails (never skips) without a disposable local MySQL and marker.
 * Not included in oj-tests/CI, and does not start the business application.
 */
@Tag("real-mysql") @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OjRealMysqlTests {
    @TempDir Path root;
    static DriverManagerDataSource admin,reader;
    static KeyPair keys;
    JdbcTemplate sql; OjIsolatedServer server; String token;
    ObjectMapper json=new ObjectMapper();
    @BeforeAll static void environment() throws Exception {
        assertEquals("YES_DISPOSABLE_LOCAL_ONLY",System.getenv("OJ_LAB_CONFIRM"),"Explicit lab consent required");
        String port=System.getenv("OJ_LAB_MYSQL_PORT"), db=System.getenv("OJ_LAB_MYSQL_DATABASE");
        assertNotNull(port);assertTrue(port.matches("[0-9]{4,5}"));
        assertNotNull(db);assertTrue(db.matches("oj_lab_[a-z0-9_]+"));
        String url="jdbc:mysql://127.0.0.1:"+port+"/"+db+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=3000&socketTimeout=5000";
        admin=new DriverManagerDataSource(url,"root",Objects.requireNonNull(System.getenv("OJ_LAB_ROOT_PASSWORD")));
        reader=new DriverManagerDataSource(url,"oj_reader",Objects.requireNonNull(System.getenv("OJ_LAB_READER_PASSWORD")));
        JdbcTemplate s=new JdbcTemplate(admin);
        assertEquals(Collections.singletonList("SMARTTT_OJ_DISPOSABLE_LAB_V1"),s.queryForList("SELECT marker FROM oj_lab_marker",String.class));
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);keys=generator.generateKeyPair();
    }
    @BeforeEach void start() throws Exception {
        sql=new JdbcTemplate(admin);
        sql.update("DELETE FROM cm_classroom_student");
        sql.update("INSERT INTO cm_classroom_student VALUES ('member-a','lab-class','student-a')");
        sql.update("UPDATE st_users SET status='LAB_ACTIVE' WHERE id='student-a'");
        sql.update("UPDATE sm_student SET status='LAB_ACTIVE' WHERE usersid='student-a'");
        sql.update("UPDATE cm_term SET enddate=DATE_ADD(CURRENT_DATE,INTERVAL 1 DAY)");
        server=new OjIsolatedServer(reader,keys,root,null);
        assertEquals(200,call("POST","/api/exam/oj/login",null,credentials("alice",System.getenv("OJ_LAB_STUDENT_PASSWORD"))).code);
        token=call("POST","/api/exam/oj/login",null,credentials("alice",System.getenv("OJ_LAB_STUDENT_PASSWORD"))).body.path("token").asText();
        assertFalse(token.isEmpty());
    }
    @AfterEach void stop(){if(server!=null)server.close();}
    Map<String,Object> credentials(String login,String password){Map<String,Object> m=new HashMap<>();m.put("login",login);m.put("password",password);return m;}
    Map<String,Object> submission(String request){Map<String,Object> m=new HashMap<>();m.put("requestId",request);m.put("courseId","lab-course");m.put("classroomId","lab-class");m.put("problemId","oj-fixture-add");m.put("language","cpp17");m.put("sourceCode","#include <iostream>\nint main(){long long a,b;std::cin>>a>>b;std::cout<<a+b;}");return m;}
    static final String HISTORY="/api/exam/oj/submissions?courseId=lab-course&classroomId=lab-class";
    static class Reply {int code;JsonNode body;String raw;}
    Reply call(String method,String path,String bearer,Object payload) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+server.port+path).openConnection();
        c.setConnectTimeout(3000);c.setReadTimeout(10000);c.setInstanceFollowRedirects(false);c.setRequestMethod(method);
        if(bearer!=null)c.setRequestProperty("Authorization","Bearer "+bearer);
        try {
            if(payload!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(json.writeValueAsBytes(payload));}}
            Reply r=new Reply();r.code=c.getResponseCode();
            try(InputStream in=r.code>=400?c.getErrorStream():c.getInputStream()){
                ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
                if(in!=null)while((n=in.read(b))!=-1){assertTrue(out.size()+n<1048576);out.write(b,0,n);}
                r.raw=new String(out.toByteArray(),"UTF-8");
                r.body=r.raw.isEmpty() || c.getContentType()==null || !c.getContentType().contains("application/json")?json.nullNode():json.readTree(r.raw);
            }return r;
        }finally{c.disconnect();}
    }
    @Test @Order(1) void realPasswordCreatesDatabaseOwnedRs256Identity() throws Exception {
        assertEquals("RS256",JWT.decode(token).getAlgorithm());assertEquals("student-a",JWT.decode(token).getClaim("id").asString());
        assertEquals(OjIsolatedServer.ISSUER,JWT.decode(token).getIssuer());
        assertEquals(200,call("GET",HISTORY,token,null).code);
        Reply scope=call("GET","/api/exam/oj/submissions/scope?classroomId=lab-class",token,null);
        assertEquals(200,scope.code);assertEquals("lab-course",scope.body.path("courseId").asText());
        Reply catalog=call("GET",HISTORY.replace("submissions?","submissions/problems?"),token,null);
        assertEquals(200,catalog.code);assertEquals(2,catalog.body.size());
        assertFalse(catalog.raw.contains("expectedOutput"));assertFalse(catalog.raw.contains("testCases"));
        assertEquals(401,call("POST","/api/exam/oj/login",null,credentials("alice","incorrect")).code);
        assertEquals(401,call("POST","/api/exam/oj/login",null,credentials("ALICE",System.getenv("OJ_LAB_STUDENT_PASSWORD"))).code);
        assertEquals(401,call("POST","/api/exam/oj/login",null,credentials("alice' OR '1'='1","incorrect")).code);
    }
    @Test @Order(2) void forgedClientIdentityAndLegacyLoginCannotMintToken() throws Exception {
        Map<String,Object> c=credentials("alice",System.getenv("OJ_LAB_STUDENT_PASSWORD"));c.put("id","student-b");
        assertEquals(400,call("POST","/api/exam/oj/login",null,c).code);
        assertEquals(404,call("POST","/api/login/user",null,c).code);
        assertEquals(404,call("POST","/login/user",null,c).code);
        assertEquals(401,call("GET",HISTORY,"forged",null).code);
        String old=JWT.create().withClaim("id","student-a").withExpiresAt(new Date(System.currentTimeMillis()+60000)).sign(Algorithm.HMAC256("lab-only-untrusted"));
        assertEquals(401,call("GET",HISTORY,old,null).code);
    }
    @Test @Order(3) void expiredTokenIsRejectedOverRealHttp() throws Exception {
        String expired=JWT.create().withIssuer(OjIsolatedServer.ISSUER).withAudience(OjIsolatedServer.AUDIENCE)
            .withClaim("id","student-a").withIssuedAt(new Date(1000)).withExpiresAt(new Date(2000))
            .sign(Algorithm.RSA256((RSAPublicKey)keys.getPublic(),(RSAPrivateKey)keys.getPrivate()));
        assertEquals(401,call("GET",HISTORY,expired,null).code);assertEquals(401,call("GET",HISTORY,null,null).code);
    }
    @Test @Order(4) void realSubmissionIdempotencyAndRestartRecovery() throws Exception {
        Reply first=call("POST","/api/exam/oj/submissions",token,submission("real-db-1"));assertEquals(200,first.code);
        assertEquals("WAITING_JUDGE",first.body.path("status").asText());
        assertEquals(first.body.path("submissionId"),call("POST","/api/exam/oj/submissions",token,submission("real-db-1")).body.path("submissionId"));
        Map<String,Object> conflict=submission("real-db-1");conflict.put("sourceCode","int main(){}");
        assertEquals(409,call("POST","/api/exam/oj/submissions",token,conflict).code);
        server.close();server=null;server=new OjIsolatedServer(reader,keys,root,null);
        Reply history=call("GET",HISTORY,token,null);assertEquals(200,history.code);assertEquals(1,history.body.size());
        assertEquals(first.body.path("submissionId"),history.body.get(0).path("submissionId"));
        for(String hidden:Arrays.asList("testCases","expectedOutput","checksum","storage-directory","fileIds","stdout","stderr"))assertFalse(history.raw.contains("\""+hidden+"\""));
    }
    @Test @Order(5) void membershipRevocationImmediatelyRejectsAndCreatesNoRecord() throws Exception {
        sql.update("DELETE FROM cm_classroom_student WHERE userId='student-a'");
        assertEquals(403,call("POST","/api/exam/oj/submissions",token,submission("revoked")).code);
        assertEquals(403,call("GET",HISTORY,token,null).code);
        assertEquals(403,call("GET","/api/exam/oj/submissions/scope?classroomId=lab-class",token,null).code);
        assertEquals(403,call("GET",HISTORY.replace("submissions?","submissions/problems?"),token,null).code);
        try(java.util.stream.Stream<Path> paths=Files.walk(root.resolve("submissions"))){assertEquals(0,paths.filter(p->p.toString().endsWith(".json")).count());}
    }
    @Test @Order(6) void differentStudentCannotReadAndCrossScopeIsRejected() throws Exception {
        Reply first=call("POST","/api/exam/oj/submissions",token,submission("private"));assertEquals(200,first.code);
        String other=call("POST","/api/exam/oj/login",null,credentials("bob",System.getenv("OJ_LAB_OTHER_PASSWORD"))).body.path("token").asText();
        assertEquals(403,call("GET",HISTORY,other,null).code);
        sql.update("INSERT INTO cm_classroom_student VALUES ('member-b','lab-class','student-b')");
        assertEquals(0,call("GET",HISTORY,other,null).body.size());
        assertEquals(404,call("GET","/api/exam/oj/submissions/"+first.body.path("submissionId").asText()+"?courseId=lab-course&classroomId=lab-class",other,null).code);
        assertEquals(403,call("GET",HISTORY.replace("lab-course","other-course"),token,null).code);
        assertEquals(403,call("GET",HISTORY.replace("lab-class","missing-class"),token,null).code);
    }
    @Test @Order(7) void unknownStatusAndExpiredTermFailClosedInRealDatabase() throws Exception {
        sql.update("UPDATE st_users SET status='UNKNOWN' WHERE id='student-a'");assertEquals(403,call("GET",HISTORY,token,null).code);
        assertEquals(401,call("POST","/api/exam/oj/login",null,credentials("alice",System.getenv("OJ_LAB_STUDENT_PASSWORD"))).code);
        sql.update("UPDATE st_users SET status='LAB_ACTIVE' WHERE id='student-a'");
        sql.update("UPDATE sm_student SET status='UNKNOWN' WHERE usersid='student-a'");
        assertEquals(403,call("GET",HISTORY,token,null).code);
        assertEquals(401,call("POST","/api/exam/oj/login",null,credentials("alice",System.getenv("OJ_LAB_STUDENT_PASSWORD"))).code);
        sql.update("UPDATE sm_student SET status='LAB_ACTIVE' WHERE usersid='student-a'");
        sql.update("UPDATE cm_term SET enddate=DATE_SUB(CURRENT_DATE,INTERVAL 1 DAY)");assertEquals(403,call("GET",HISTORY,token,null).code);
    }
    @Test @Order(8) void applicationDatabaseAccountCannotWriteBusinessTables() {
        assertThrows(org.springframework.dao.DataAccessException.class,()->new JdbcTemplate(reader).update("DELETE FROM st_users"));
    }
}
