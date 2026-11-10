package com.example.smartttexam.oj;

import com.example.smartttexam.oj.http.*;
import com.example.smartttexam.oj.OjModels.Owner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.HttpStatus.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Test-only identity adapter. Not JWT integration or a production membership rule. */
public class OjHttpTests {
    @TempDir Path root;
    FileOjSubmissionRepository repo;
    OjSubmissionService service;
    MockMvc mvc;
    ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup() throws Exception {
        repo=new FileOjSubmissionRepository(root); service=new OjSubmissionService(repo,new FixedOjProblemCatalog());
        mvc=MockMvcBuilders.standaloneSetup(new OjHttpController(service,(request,course,classroom)->{
            String identity=request.getHeader("Test-Identity");
            if (identity==null) throw new ResponseStatusException(UNAUTHORIZED);
            if (!Arrays.asList("alice","bob").contains(identity) || !course.equals("course") || !classroom.equals("class"))
                throw new ResponseStatusException(FORBIDDEN);
            return new Owner(identity,course,classroom);
        })).build();
    }
    @AfterEach void close() throws Exception { repo.close(); }
    Map<String,Object> body(String key) {
        Map<String,Object> b=new LinkedHashMap<>(); b.put("requestId",key); b.put("courseId","course");
        b.put("classroomId","class"); b.put("problemId",FixedOjProblemCatalog.PROBLEM_ID);
        b.put("language","cpp17"); b.put("sourceCode","int main(){}"); return b;
    }
    String save(String key) throws Exception {
        return mvc.perform(post("/exam/oj/submissions").header("Test-Identity","alice")
                .contentType("application/json").content(json.writeValueAsBytes(body(key))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    @Test void submitAndHistory() throws Exception {
        save("first");
        mvc.perform(get("/exam/oj/submissions").header("Test-Identity","alice").param("courseId","course").param("classroomId","class"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("WAITING_JUDGE"));
    }
    @Test void rejectsAnonymous() throws Exception {
        mvc.perform(post("/exam/oj/submissions").contentType("application/json").content(json.writeValueAsBytes(body("x"))))
                .andExpect(status().isUnauthorized());
        assertTrue(service.history(new Owner("alice","course","class"),null,0,20).isEmpty());
    }
    @Test void rejectsForgedIdentityInBody() throws Exception {
        Map<String,Object>b=body("spoof"); b.put("studentId","bob");
        mvc.perform(post("/exam/oj/submissions").header("Test-Identity","alice").contentType("application/json")
                .content(json.writeValueAsBytes(b))).andExpect(status().isBadRequest());
    }
    @Test void isolatesOtherStudentEvenWithQueryIdentity() throws Exception {
        String id=json.readTree(save("private")).get("submissionId").asText();
        mvc.perform(get("/exam/oj/submissions/"+id).header("Test-Identity","bob").param("studentId","alice")
                .param("courseId","course").param("classroomId","class")).andExpect(status().isNotFound());
    }
    @Test void deniesCrossCourseAndClassroom() throws Exception {
        for (String[] scope:Arrays.asList(new String[]{"other","class"},new String[]{"course","other"}))
            mvc.perform(get("/exam/oj/submissions").header("Test-Identity","alice").param("courseId",scope[0])
                    .param("classroomId",scope[1])).andExpect(status().isForbidden());
    }
    @Test void preservesIdempotencyAndConflict() throws Exception {
        assertEquals(save("same"),save("same")); Map<String,Object>b=body("same"); b.put("sourceCode","int main(){return 1;}");
        mvc.perform(post("/exam/oj/submissions").header("Test-Identity","alice").contentType("application/json")
                .content(json.writeValueAsBytes(b))).andExpect(status().isConflict());
    }
    @Test void responseContainsOnlyAllowedFields() throws Exception {
        Set<String> fields=new HashSet<>(); json.readTree(save("safe")).fieldNames().forEachRemaining(fields::add);
        assertEquals(new HashSet<>(Arrays.asList("submissionId","requestId","courseId","classroomId","problemId",
                "language","sourceCode","submittedAt","status","attemptNumber")),fields);
    }
    @Test void rejectsPaginationAndMissingParameters() throws Exception {
        mvc.perform(get("/exam/oj/submissions").header("Test-Identity","alice").param("courseId","course")
                .param("classroomId","class").param("limit","101")).andExpect(status().isBadRequest());
        mvc.perform(get("/exam/oj/submissions").header("Test-Identity","alice")).andExpect(status().isBadRequest());
    }
    @Test void storageFailureDoesNotLeakException() throws Exception {
        repo.close();
        mvc.perform(post("/exam/oj/submissions").header("Test-Identity","alice").contentType("application/json")
                .content(json.writeValueAsBytes(body("closed"))))
                .andExpect(status().isServiceUnavailable()).andExpect(content().json("{\"code\":\"STORAGE_UNAVAILABLE\"}"));
    }
    @Test void disabledByDefault() {
        try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()) {
            context.register(OjHttpConfiguration.class,OjHttpController.class); context.refresh();
            assertEquals(0,context.getBeansOfType(OjHttpController.class).size());
            assertEquals(0,context.getBeansOfType(FileOjSubmissionRepository.class).size());
        }
    }
    @Test void enabledWithoutIdentityAdapterFailsClosed() {
        Path unopened=root.resolve("never-created");
        try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()) {
            context.setEnvironment(new MockEnvironment().withProperty("oj.http.enabled","true")
                    .withProperty("oj.http.storage-directory",unopened.toString()));
            context.register(OjHttpConfiguration.class,OjHttpController.class);
            assertThrows(org.springframework.beans.BeansException.class,context::refresh);
        }
        assertFalse(Files.exists(unopened));
    }

    @Test void ownedDetailAndAnonymousFollowup() throws Exception {
        String saved=save("detail"); String id=json.readTree(saved).get("submissionId").asText();
        mvc.perform(get("/exam/oj/submissions/"+id).header("Test-Identity","alice")
                .param("courseId","course").param("classroomId","class"))
                .andExpect(status().isOk()).andExpect(content().json(saved));
        mvc.perform(get("/exam/oj/submissions/"+id).param("courseId","course").param("classroomId","class"))
                .andExpect(status().isUnauthorized());
    }

    @Test void configuredRepositoryClosesWithContext() throws Exception {
        Path directory=root.resolve("lifecycle");
        try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()) {
            context.setEnvironment(new MockEnvironment().withProperty("oj.http.enabled","true")
                    .withProperty("oj.http.storage-directory",directory.toString()));
            context.getBeanFactory().registerSingleton("testOnlyAccess",(OjHttpAccess)(request,course,classroom)->new Owner("alice",course,classroom));
            context.register(OjHttpConfiguration.class); context.refresh();
            assertNotNull(context.getBean(OjSubmissionService.class));
            assertThrows(java.io.IOException.class,()->new FileOjSubmissionRepository(directory));
        }
        try(FileOjSubmissionRepository reopened=new FileOjSubmissionRepository(directory)) {
            assertTrue(reopened.history(new Owner("alice","course","class"),null,0,20).isEmpty());
        }
    }

    @Test void verifiesLegacyJwtBehaviorWithoutUsingRealIssuer() {
        String key="test-only-signing-key";
        com.auth0.jwt.algorithms.Algorithm algorithm=com.auth0.jwt.algorithms.Algorithm.HMAC256(key);
        String valid=com.auth0.jwt.JWT.create().withClaim("id","alice").withClaim("obsdeep",103L)
                .withExpiresAt(new Date(System.currentTimeMillis()+60000)).sign(algorithm);
        assertEquals("alice",com.example.smartttexam.Utils.JwtTokenUtils.parseToken(valid,key).getId());
        assertThrows(com.auth0.jwt.exceptions.JWTVerificationException.class,
                ()->com.example.smartttexam.Utils.JwtTokenUtils.parseToken(valid,"wrong-test-key"));
        String expired=com.auth0.jwt.JWT.create().withClaim("id","alice").withClaim("obsdeep",103L)
                .withExpiresAt(new Date(System.currentTimeMillis()-60000)).sign(algorithm);
        assertThrows(com.auth0.jwt.exceptions.JWTVerificationException.class,
                ()->com.example.smartttexam.Utils.JwtTokenUtils.parseToken(expired,key));
        String noExpiry=com.auth0.jwt.JWT.create().withClaim("id","alice").withClaim("obsdeep",103L).sign(algorithm);
        // Evidence for the production-auth blocker: legacy verification does not require exp to exist.
        assertEquals("alice",com.example.smartttexam.Utils.JwtTokenUtils.parseToken(noExpiry,key).getId());
    }
}
