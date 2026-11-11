package com.example.smartttexam.oj;

import com.example.smartttexam.oj.OjModels.*;
import com.example.smartttexam.oj.http.*;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.HttpStatus.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Isolated file catalog + real controller/authorization policy, no Spring application or databases. */
public class OjCatalogTests {
    @TempDir Path root;
    ObjectMapper json=new ObjectMapper();
    Owner alice=new Owner("alice","course","class");
    FileOjProblemCatalog.Document doc;
    @BeforeEach void data() {
        doc=new FileOjProblemCatalog.Document();doc.schemaVersion=1;doc.problems=new ArrayList<>();
        doc.problems.add(entry("sum","course","class",true));
        doc.problems.add(entry("multiply","course","class",true));
        doc.problems.add(entry("unpublished","course","class",false));
        doc.problems.add(entry("other","other-course","other-class",true));
    }
    FileOjProblemCatalog.Entry entry(String id,String course,String classroom,boolean published) {
        FileOjProblemCatalog.Entry e=new FileOjProblemCatalog.Entry();e.courseId=course;e.classroomId=classroom;e.published=published;
        e.problem=new FixedOjProblemCatalog().get(FixedOjProblemCatalog.PROBLEM_ID);e.problem.setProblemId(id);
        if(id.equals("multiply")) {
            e.problem.setStatement("输入两个整数，输出乘积。");
            e.problem.setTestCases(Collections.singletonList(new TestCase("product","6 7\n","42\n")));
        }
        return e;
    }
    Path write(String name) throws Exception {Path file=root.resolve(name);Files.write(file,json.writeValueAsBytes(doc));return file;}
    FileOjProblemCatalog catalog() throws Exception {return FileOjProblemCatalog.load(write("catalog.json"));}
    static class Data implements OjAuthorizationData {
        boolean member=true; boolean duplicate=false; boolean missing=false;
        public List<Student> studentsByUserId(String id){return Collections.singletonList(new Student(id,"1",true));}
        public boolean courseExists(String id){return "course".equals(id);}
        public List<String> courseIdsByClassroom(String id){return !"class".equals(id)||missing?Collections.emptyList():duplicate?Arrays.asList("course","course"):Collections.singletonList("course");}
        public int membershipCount(String classroom,String user){return member && "alice".equals(user)?1:0;}
    }
    OjProductionAccess access(Data data){return new OjProductionAccess(r->{String user=r.getHeader("Fixture-Identity");if(user==null)throw new ResponseStatusException(UNAUTHORIZED);return user;},data);}
    Map<String,Object> body(String id,String request) {
        Map<String,Object> b=new LinkedHashMap<>();b.put("courseId","course");b.put("classroomId","class");
        b.put("requestId",request);b.put("problemId",id);b.put("language","cpp17");b.put("sourceCode","int main(){}");return b;
    }
    @Test void scopedCatalogHasMultipleProblemsAndCopiesSnapshots() throws Exception {
        FileOjProblemCatalog c=catalog();assertEquals(2,c.list(alice).size());
        assertEquals("42\n",c.get(alice,"multiply").getTestCases().get(0).getExpectedOutput());
        c.get(alice,"sum").setStatement("tampered");
        assertNotEquals("tampered",c.get(alice,"sum").getStatement());
        assertThrows(IllegalArgumentException.class,()->c.get(alice,"unpublished"));
        assertThrows(IllegalArgumentException.class,()->c.get(alice,"other"));
        assertTrue(c.list(new Owner("alice","course","missing")).isEmpty());
    }
    @Test void publicDirectoryAndResolvedScopeExposeOnlyAllowlistedMetadata() throws Exception {
        try(FileOjSubmissionRepository repo=new FileOjSubmissionRepository(root.resolve("store"))) {
            MockMvc mvc=MockMvcBuilders.standaloneSetup(new OjHttpController(new OjSubmissionService(repo,catalog()),access(new Data()))).build();
            String raw=mvc.perform(get("/exam/oj/submissions/problems").header("Fixture-Identity","alice").param("courseId","course").param("classroomId","class"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            JsonNode values=json.readTree(raw);assertEquals(2,values.size());
            Set<String> names=new HashSet<>();values.get(0).fieldNames().forEachRemaining(names::add);
            assertEquals(new HashSet<>(Arrays.asList("problemId","version","statement","language","cpuTimeLimitMs","wallTimeLimitMs","memoryLimitBytes","outputLimitBytes")),names);
            mvc.perform(get("/exam/oj/submissions/scope").header("Fixture-Identity","alice").param("classroomId","class"))
                .andExpect(status().isOk()).andExpect(content().json("{\"courseId\":\"course\",\"classroomId\":\"class\"}"));
        }
    }
    @Test void everyDirectoryAndScopeCallRechecksMembership() throws Exception {
        Data data=new Data();
        try(FileOjSubmissionRepository repo=new FileOjSubmissionRepository(root.resolve("store"))) {
            MockMvc mvc=MockMvcBuilders.standaloneSetup(new OjHttpController(new OjSubmissionService(repo,catalog()),access(data))).build();
            mvc.perform(get("/exam/oj/submissions/problems").param("courseId","course").param("classroomId","class")).andExpect(status().isUnauthorized());
            mvc.perform(get("/exam/oj/submissions/scope").param("classroomId","class")).andExpect(status().isUnauthorized());
            data.member=false;
            mvc.perform(get("/exam/oj/submissions/problems").header("Fixture-Identity","alice").param("courseId","course").param("classroomId","class")).andExpect(status().isForbidden());
            mvc.perform(get("/exam/oj/submissions/scope").header("Fixture-Identity","alice").param("classroomId","class")).andExpect(status().isForbidden());
            mvc.perform(post("/exam/oj/submissions").header("Fixture-Identity","alice").contentType("application/json").content(json.writeValueAsBytes(body("sum","denied")))).andExpect(status().isForbidden());
            assertTrue(repo.history(alice,null,0,20).isEmpty());
        }
    }
    @Test void ambiguousOrMissingClassroomCannotResolveCourse() {
        Data data=new Data();OjProductionAccess a=access(data);
        MockHttpServletRequest r=new MockHttpServletRequest();r.addHeader("Fixture-Identity","alice");
        data.duplicate=true;assertEquals(FORBIDDEN,assertThrows(ResponseStatusException.class,()->a.resolveClassroom(r,"class")).getStatus());
        data.duplicate=false;data.missing=true;assertEquals(FORBIDDEN,assertThrows(ResponseStatusException.class,()->a.resolveClassroom(r,"class")).getStatus());
        OjHttpAccess legacy=(request,course,classroom)->alice;
        assertEquals(FORBIDDEN,assertThrows(ResponseStatusException.class,()->legacy.resolveClassroom(r,"class")).getStatus());
    }
    @Test void unpublishedOrForeignProblemNeverCreatesSubmission() throws Exception {
        try(FileOjSubmissionRepository repo=new FileOjSubmissionRepository(root.resolve("store"))) {
            OjSubmissionService s=new OjSubmissionService(repo,catalog());
            for(String id:Arrays.asList("unpublished","other","missing"))
                assertThrows(IllegalArgumentException.class,()->s.submit(alice,id,id,"cpp17","int main(){}"));
            assertTrue(s.history(alice,null,0,20).isEmpty());
            assertEquals("multiply",s.submit(alice,"allowed","multiply","cpp17","int main(){}").getProblem().getProblemId());
        }
    }
    @Test void retryAndHistorySurviveRevisionUnpublishAndRestart() throws Exception {
        String id;
        try(FileOjSubmissionRepository repo=new FileOjSubmissionRepository(root.resolve("store"))) {
            id=new OjSubmissionService(repo,catalog()).submit(alice,"retry","sum","cpp17","int main(){}").getSubmissionId();
        }
        doc.problems.get(0).problem.setVersion("2");doc.problems.get(0).problem.setStatement("revised");doc.problems.get(0).published=false;
        try(FileOjSubmissionRepository repo=new FileOjSubmissionRepository(root.resolve("store"))) {
            OjSubmissionService s=new OjSubmissionService(repo,catalog());
            Submission retry=s.submit(alice,"retry","sum","cpp17","int main(){}");assertEquals(id,retry.getSubmissionId());
            assertEquals("1",retry.getProblem().getVersion());assertEquals(1,s.history(alice,"sum",0,20).size());
            assertThrows(IllegalArgumentException.class,()->s.submit(alice,"new","sum","cpp17","int main(){}"));
            assertThrows(IllegalArgumentException.class,()->s.submit(alice,"retry","sum","cpp17","changed"));
            assertThrows(IllegalArgumentException.class,()->s.submit(alice,"retry","multiply","cpp17","int main(){}"));
            assertNull(s.find(new Owner("bob","course","class"),id));
        }
    }
    @Test void catalogRejectsTrailingDataDuplicatesAndMalformedUnicodeWithoutRewriting() throws Exception {
        byte[] valid=json.writeValueAsBytes(doc);
        for(String suffix:Arrays.asList("{}","garbage")) {
            Path p=root.resolve("invalid.json");byte[] content=(new String(valid,StandardCharsets.UTF_8)+suffix).getBytes(StandardCharsets.UTF_8);Files.write(p,content);
            assertThrows(IOException.class,()->FileOjProblemCatalog.load(p));assertArrayEquals(content,Files.readAllBytes(p));
        }
        Path p=root.resolve("duplicate.json");Files.write(p,"{\"schemaVersion\":1,\"schemaVersion\":1,\"problems\":[]}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class,()->FileOjProblemCatalog.load(p));
        Files.write(p,new byte[]{(byte)0xc3,(byte)0x28});assertThrows(IOException.class,()->FileOjProblemCatalog.load(p));
        doc.problems.get(0).problem.setStatement("invalid "+(char)0xd800);
        Path escaped=write("surrogate.json");assertThrows(IOException.class,()->FileOjProblemCatalog.load(escaped));
    }
    @Test void catalogRejectsLossyLimitsMissingTypesAndUnknownFields() throws Exception {
        JsonNode source=json.valueToTree(doc);
        for(String field:Arrays.asList("cpuTimeLimitMs","wallTimeLimitMs","memoryLimitBytes","outputLimitBytes")) {
            for(String bad:Arrays.asList("1.5","9223372036854775808","\"1000\"","null")) {
                JsonNode copy=source.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)copy.path("problems").get(0).path("problem")).set(field,json.readTree(bad));
                Path p=root.resolve("invalid.json");Files.write(p,json.writeValueAsBytes(copy));assertThrows(IOException.class,()->FileOjProblemCatalog.load(p));
            }
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode)source).put("unexpected","ignored?");
        Path p=root.resolve("unknown.json");Files.write(p,json.writeValueAsBytes(source));assertThrows(IOException.class,()->FileOjProblemCatalog.load(p));
    }
    @Test void catalogRejectsDuplicateBindingsAndConflictingVersions() throws Exception {
        doc.problems.add(entry("sum","course","class",true));Path duplicate=write("duplicate.json");
        assertThrows(IOException.class,()->FileOjProblemCatalog.load(duplicate));
        doc.problems.remove(doc.problems.size()-1);
        FileOjProblemCatalog.Entry another=entry("sum","another","another",true);another.problem.setStatement("conflicting");doc.problems.add(another);
        Path conflict=write("conflict.json");assertThrows(IOException.class,()->FileOjProblemCatalog.load(conflict));
    }
    @Test void installIsAtomicAndRejectsUnversionedChangesOrSilentDeletion() throws Exception {
        Path target=root.resolve("installed.json");FileOjProblemCatalog.install(write("source.json"),target);byte[] original=Files.readAllBytes(target);
        doc.problems.get(0).problem.setStatement("changed");Path bad=write("source.json");
        assertThrows(IOException.class,()->FileOjProblemCatalog.install(bad,target));assertArrayEquals(original,Files.readAllBytes(target));
        doc.problems.get(0).problem.setVersion("2");FileOjProblemCatalog.install(write("source.json"),target);
        assertEquals("changed",FileOjProblemCatalog.load(target).get(alice,"sum").getStatement());
        original=Files.readAllBytes(target);doc.problems.remove(0);Path deleted=write("source.json");
        assertThrows(IOException.class,()->FileOjProblemCatalog.install(deleted,target));assertArrayEquals(original,Files.readAllBytes(target));
    }
    @Test void unpublishAndEmptyDirectoryRemainValidAndLimitsAreBounded() throws Exception {
        for(FileOjProblemCatalog.Entry e:doc.problems)e.published=false;
        assertTrue(catalog().list(alice).isEmpty());
        doc.problems.clear();assertTrue(catalog().list(alice).isEmpty());
        doc.problems.add(entry("sum","course","class",true));doc.problems.get(0).problem.setCpuTimeLimitMs(10001);
        Path p=write("limits.json");assertThrows(IOException.class,()->FileOjProblemCatalog.load(p));
        assertThrows(IOException.class,()->FileOjProblemCatalog.load(Paths.get("relative.json")));
    }
    @Test void hiddenChangesRequireNewTestVersionWithoutRewriting() throws Exception {
        Path target=root.resolve("installed.json");FileOjProblemCatalog.install(write("source.json"),target);
        byte[] original=Files.readAllBytes(target);
        Problem p=doc.problems.get(0).problem;p.setVersion("2");
        for(TestCase changed:Arrays.asList(new TestCase("sum","changed input","3\n"),new TestCase("sum","1 2\n","changed output"))) {
            p.setTestCases(Collections.singletonList(changed));Path source=write("source.json");byte[] input=Files.readAllBytes(source);
            assertThrows(IOException.class,()->FileOjProblemCatalog.install(source,target));
            assertArrayEquals(original,Files.readAllBytes(target));assertArrayEquals(input,Files.readAllBytes(source));
        }
        p.setTestVersion("2");FileOjProblemCatalog.install(write("source.json"),target);
        assertEquals("2",FileOjProblemCatalog.load(target).get(alice,"sum").getTestVersion());
    }
    @Test void sameTestVersionCannotConflictAcrossClassrooms() throws Exception {
        FileOjProblemCatalog.Entry other=entry("sum","another","another",true);
        other.problem.setVersion("2");other.problem.setTestCases(Collections.singletonList(new TestCase("sum","different","output")));
        doc.problems.add(other);Path source=write("conflict.json");
        assertThrows(IOException.class,()->FileOjProblemCatalog.load(source));
    }
    @Test void httpEnabledWithoutPrivateCatalogFailsClosed() {
        try(org.springframework.context.annotation.AnnotationConfigApplicationContext c=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            c.setEnvironment(new org.springframework.mock.env.MockEnvironment().withProperty("oj.http.enabled","true")
                .withProperty("oj.http.storage-directory",root.resolve("closed-store").toString()));
            c.getBeanFactory().registerSingleton("access",(OjHttpAccess)(request,course,classroom)->alice);
            c.register(OjHttpConfiguration.class);assertThrows(org.springframework.beans.BeansException.class,c::refresh);
        }
        // Repository opened before configuration failure must still have been closed by Spring.
        assertDoesNotThrow(()->{try(FileOjSubmissionRepository ignored=new FileOjSubmissionRepository(root.resolve("closed-store"))) { }});
    }
}
