package com.example.smartttexam.oj;

import com.example.smartttexam.oj.http.*;
import com.example.smartttexam.oj.OjModels.Owner;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.security.*;
import java.security.interfaces.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.HttpStatus.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class OjProductionAccessTests {
    static KeyPair keys;
    static Algorithm signer;
    @TempDir Path root;
    Data data;
    OjProductionAccess access;
    @BeforeAll static void keys() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        keys=generator.generateKeyPair();
        signer=Algorithm.RSA256((RSAPublicKey)keys.getPublic(),(RSAPrivateKey)keys.getPrivate());
    }
    @BeforeEach void setup() {
        data=new Data(); access=new OjProductionAccess(new OjStrictJwtIdentity((RSAPublicKey)keys.getPublic(),"trusted-test-issuer","oj"),data);
    }
    String token(String id, boolean expiration) {
        com.auth0.jwt.JWTCreator.Builder b=JWT.create().withIssuer("trusted-test-issuer").withAudience("oj")
                .withClaim("id",id).withIssuedAt(new Date(System.currentTimeMillis()-1000));
        if(expiration)b.withExpiresAt(new Date(System.currentTimeMillis()+60000));
        return b.sign(signer);
    }
    MockHttpServletRequest request(String token) {
        MockHttpServletRequest request=new MockHttpServletRequest();
        if(token!=null)request.addHeader("Authorization","Bearer "+token);
        return request;
    }
    void denied(MockHttpServletRequest request,org.springframework.http.HttpStatus expected,String course,String classroom) {
        assertEquals(expected,assertThrows(ResponseStatusException.class,()->access.authorize(request,course,classroom)).getStatus());
    }
    @Test void legitimateStudent() {
        assertEquals(new Owner("alice","course","class"),access.authorize(request(token("alice",true)),"course","class"));
        assertEquals("alice",data.lastUser);
    }
    @Test void missingToken() { denied(request(null),UNAUTHORIZED,"course","class"); }
    @Test void forgedAndLegacyTestToken() {
        String legacy=JWT.create().withClaim("id","alice").withIssuer("trusted-test-issuer").withAudience("oj")
                .withIssuedAt(new Date()).withExpiresAt(new Date(System.currentTimeMillis()+60000))
                .sign(Algorithm.HMAC256("test-only-legacy-key"));
        denied(request(legacy),UNAUTHORIZED,"course","class");
        denied(request("not-a-jwt"),UNAUTHORIZED,"course","class");
    }
    @Test void expiredToken() {
        String expired=JWT.create().withIssuer("trusted-test-issuer").withAudience("oj").withClaim("id","alice")
                .withIssuedAt(new Date(System.currentTimeMillis()-120000)).withExpiresAt(new Date(System.currentTimeMillis()-60000)).sign(signer);
        denied(request(expired),UNAUTHORIZED,"course","class");
    }
    @Test void missingExpiration() { denied(request(token("alice",false)),UNAUTHORIZED,"course","class"); }
    @Test void missingOrWrongRequiredClaims() {
        for(int variant=0;variant<5;variant++) {
            com.auth0.jwt.JWTCreator.Builder b=JWT.create().withIssuer(variant==0?"wrong":"trusted-test-issuer")
                    .withAudience(variant==1?"wrong":"oj").withExpiresAt(new Date(System.currentTimeMillis()+60000));
            if(variant!=2)b.withClaim("id","alice");
            if(variant!=3)b.withIssuedAt(new Date(System.currentTimeMillis()+(variant==4?30000:-1000)));
            denied(request(b.sign(signer)),UNAUTHORIZED,"course","class");
        }
    }
    @Test void nonStudentAndIneligibleAccount() {
        data.students=Collections.singletonList(new OjAuthorizationData.Student("alice","2",true));
        denied(request(token("alice",true)),FORBIDDEN,"course","class");
        data.students=Collections.singletonList(new OjAuthorizationData.Student("alice","1",false));
        denied(request(token("alice",true)),FORBIDDEN,"course","class");
    }
    @Test void clientIdentityCannotOverrideJwt() {
        MockHttpServletRequest r=request(token("alice",true)); r.addParameter("studentId","bob");
        r.addParameter("roleId","administrator"); r.addHeader("Test-Identity","bob");
        assertEquals("alice",access.authorize(r,"course","class").getStudentId()); assertEquals("alice",data.lastUser);
    }
    @Test void crossCourseAndClassroom() {
        denied(request(token("alice",true)),FORBIDDEN,"other","class");
        denied(request(token("alice",true)),FORBIDDEN,"course","other");
        data.mappedCourse="different"; denied(request(token("alice",true)),FORBIDDEN,"course","class");
    }
    @Test void removedMembershipIsRechecked() {
        MockHttpServletRequest r=request(token("alice",true)); access.authorize(r,"course","class");
        data.members=0; denied(r,FORBIDDEN,"course","class"); assertEquals(2,data.memberQueries);
    }
    @Test void nonexistentCourseOrClassroom() {
        data.exists=false; denied(request(token("alice",true)),FORBIDDEN,"course","class");
        data.exists=true; data.courses=Collections.emptyList(); denied(request(token("alice",true)),FORBIDDEN,"course","class");
    }
    @Test void ambiguousOrMismatchedDataDenied() {
        data.students=Arrays.asList(new OjAuthorizationData.Student("alice","1",true),new OjAuthorizationData.Student("alice","1",true));
        denied(request(token("alice",true)),FORBIDDEN,"course","class");
        data.students=Collections.singletonList(new OjAuthorizationData.Student("bob","1",true));
        denied(request(token("alice",true)),FORBIDDEN,"course","class");
        data.students=Collections.singletonList(new OjAuthorizationData.Student("alice","1",true)); data.members=2;
        denied(request(token("alice",true)),FORBIDDEN,"course","class");
    }
    @Test void testIssuerHasNoHttpHandler() {
        try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()) {
            context.register(com.example.smartttexam.controller.TestTokenController.class); context.refresh();
            RequestMappingHandlerMapping mapping=new RequestMappingHandlerMapping();
            mapping.setApplicationContext(context); mapping.afterPropertiesSet();
            assertTrue(mapping.getHandlerMethods().isEmpty());
        }
    }
    @Test void denialAndDataFailureCannotPersist() throws Exception {
        try(FileOjSubmissionRepository repository=new FileOjSubmissionRepository(root)) {
            org.springframework.test.web.servlet.MockMvc mvc=MockMvcBuilders.standaloneSetup(new OjHttpController(
                    new OjSubmissionService(repository,new FixedOjProblemCatalog()),access)).build();
            String body="{\"requestId\":\"denied\",\"courseId\":\"course\",\"classroomId\":\"class\",\"problemId\":\"oj-fixture-add\",\"language\":\"cpp17\",\"sourceCode\":\"int main(){}\"}";
            data.members=0;
            mvc.perform(post("/exam/oj/submissions").header("Authorization","Bearer "+token("alice",true))
                    .contentType("application/json").content(body)).andExpect(status().isForbidden());
            data.broken=true;
            mvc.perform(post("/exam/oj/submissions").header("Authorization","Bearer "+token("alice",true))
                    .contentType("application/json").content(body)).andExpect(status().isServiceUnavailable())
                    .andExpect(content().json("{\"code\":\"SERVICE_UNAVAILABLE\"}"));
            assertTrue(repository.history(new Owner("alice","course","class"),null,0,20).isEmpty());
        }
    }
    static class Data implements OjAuthorizationData {
        List<Student> students=Collections.singletonList(new Student("alice","1",true));
        boolean exists=true,broken=false;
        String mappedCourse="course",lastUser;
        List<String> courses=null;
        int members=1,memberQueries;
        public List<Student> studentsByUserId(String userId) { if(broken)throw new IllegalStateException("internal database detail"); lastUser=userId; return students; }
        public boolean courseExists(String course) {return exists && "course".equals(course);}
        public List<String> courseIdsByClassroom(String classroom) {return courses!=null?courses:"class".equals(classroom)?Collections.singletonList(mappedCourse):Collections.emptyList();}
        public int membershipCount(String classroom,String userId) {lastUser=userId;memberQueries++;return members;}
    }
}
