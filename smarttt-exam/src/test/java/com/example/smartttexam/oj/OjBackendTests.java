package com.example.smartttexam.oj;

import com.example.smartttexam.oj.http.*;
import com.example.smartttexam.oj.judge.*;
import com.example.smartttexam.oj.OjModels.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Local HTTP protocol fixture, never a sandbox: no source is compiled or executed here. */
public class OjBackendTests {
    @TempDir Path root;
    ObjectMapper json=new ObjectMapper();
    HttpServer server;
    ExecutorService http;
    AtomicInteger runs=new AtomicInteger(),deletes=new AtomicInteger();
    List<JsonNode> commands=new CopyOnWriteArrayList<>();
    volatile String runtime="Accepted",compile="Accepted",output="5\n";
    volatile boolean malformed,trailing;volatile int delay;
    volatile Map<String,JsonNode> compileOverrides=Collections.emptyMap(),runtimeOverrides=Collections.emptyMap();
    volatile long artifactBytes=32768;
    static KeyPair keys;
    @BeforeAll static void keys() throws Exception {KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);keys=g.generateKeyPair();}
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http=Executors.newFixedThreadPool(2);server.setExecutor(http);
        server.createContext("/",exchange->{
            try {
                if(exchange.getRequestMethod().equals("DELETE")){deletes.incrementAndGet();exchange.sendResponseHeaders(204,-1);return;}
                JsonNode command=json.readTree(exchange.getRequestBody()).path("cmd").get(0);commands.add(command);runs.incrementAndGet();
                if(delay>0)try{Thread.sleep(delay);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                boolean isCompile=command.path("args").get(0).asText().contains("g++");
                String state=isCompile?compile:runtime;
                Map<String,Object> response=new HashMap<>();response.put("status",state);
                response.put("exitStatus",state.equals("Nonzero Exit Status") || state.equals("Non Zero Exit Status")?1:0);
                response.put("time",123L);response.put("memory",456L);
                if(isCompile) {
                    if(state.equals("Accepted") && artifactBytes>command.path("copyOutMax").asLong()) {
                        response.put("status","File Error");
                        Map<String,String> error=new HashMap<>();error.put("name","main");error.put("type","CopyOutSizeExceeded");
                        response.put("fileError",Collections.singletonList(error));
                    } else if(state.equals("Accepted"))response.put("fileIds",Collections.singletonMap("main","fixture-cache"));
                }
                else {
                    String input=command.path("files").get(0).path("content").asText();
                    String answer=output;
                    if(output.equals("AUTO")){String[] v=input.trim().split(" +");answer=Long.toString(Long.parseLong(v[0])+Long.parseLong(v[1]))+"\n";}
                    response.put("files",Collections.singletonMap("stdout",answer));
                }
                for(Map.Entry<String,JsonNode> item:(isCompile?compileOverrides:runtimeOverrides).entrySet()) {
                    if(item.getValue().isMissingNode())response.remove(item.getKey());
                    else response.put(item.getKey(),item.getValue());
                }
                byte[] body=malformed?"{}".getBytes("UTF-8"):json.writeValueAsBytes(Collections.singletonList(response));
                if(trailing)body=(new String(body,"UTF-8")+" {}").getBytes("UTF-8");
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }finally{exchange.close();}
        });server.start();
    }
    @AfterEach void stop() throws Exception {server.stop(0);http.shutdownNow();assertTrue(http.awaitTermination(5,TimeUnit.SECONDS));}
    GoJudgeClient client(int timeout){return new GoJudgeClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),timeout);}
    Submission record() throws Exception {
        try(FileOjSubmissionRepository repository=new FileOjSubmissionRepository(root.resolve("seed"))){return new OjSubmissionService(repository,new FixedOjProblemCatalog()).submit(new Owner("student","course","class"),"key",FixedOjProblemCatalog.PROBLEM_ID,"cpp17","int main(){}");}
    }
    @Test void protocolCompileOnceRunAllCases() throws Exception {
        output="AUTO";GoJudgeClient.Result r=client(1000).judge(record());assertEquals(Verdict.AC,r.verdict);
        assertEquals(5,runs.get());assertEquals(1,deletes.get());assertEquals(123,r.timeNs);
        JsonNode c=commands.get(0);assertEquals("-std=c++17",c.path("args").get(2).asText());
        assertEquals("int main(){}",c.path("copyIn").path("main.cpp").path("content").asText());
        assertEquals(1000000000L,commands.get(1).path("cpuLimit").asLong());
        assertEquals(128L*1024*1024,commands.get(1).path("memoryLimit").asLong());
        assertEquals(65536,commands.get(1).path("files").get(1).path("max").asLong());
        assertEquals("fixture-cache",commands.get(1).path("copyIn").path("main").path("fileId").asText());
    }
    @Test void compileFailureSkipsRuns() throws Exception {compile="Nonzero Exit Status";assertEquals(Verdict.CE,client(1000).judge(record()).verdict);assertEquals(1,runs.get());}
    @Test void acceptedExecutionStillNeedsOutputComparison() throws Exception {output="wrong";assertEquals(Verdict.WA,client(1000).judge(record()).verdict);assertEquals(1,deletes.get());}
    @Test void resourceVerdicts() throws Exception {
        Submission s=record();String[] states={"Time Limit Exceeded","Memory Limit Exceeded","Output Limit Exceeded","Signalled","Nonzero Exit Status","Internal Error"};
        Verdict[] expected={Verdict.TLE,Verdict.MLE,Verdict.OLE,Verdict.RE,Verdict.RE,Verdict.SYSTEM_ERROR};
        for(int i=0;i<states.length;i++){runtime=states[i];assertEquals(expected[i],client(1000).judge(s).verdict);}
    }
    @Test void malformedResponseFailsClosed() throws Exception {malformed=true;assertThrows(IOException.class,()->client(1000).judge(record()));malformed=false;trailing=true;assertThrows(IOException.class,()->client(1000).judge(record()));}
    @Test void timeoutIsBounded() throws Exception {delay=500;assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),()->assertThrows(IOException.class,()->client(100).judge(record())));}
    @Test void untrustedEndpointAndLimitsRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new GoJudgeClient(URI.create("file:///tmp/x"),1000));
        Submission s=record();s.getProblem().setMemoryLimitBytes(Long.MAX_VALUE);assertThrows(IOException.class,()->client(1000).judge(s));assertEquals(0,runs.get());
    }
    @Test void durableResultsAndNoDuplicateDispatch() throws Exception {
        output="AUTO";Path source=root.resolve("submissions"),results=root.resolve("results");
        try(FileOjSubmissionRepository r=new FileOjSubmissionRepository(source);OjJudgeJournal j=new OjJudgeJournal(results);OjJudgeDispatcher d=new OjJudgeDispatcher(r,j,client(1000))){
            Submission s=new OjSubmissionService(r,new FixedOjProblemCatalog()).submit(new Owner("student","course","class"),"one",FixedOjProblemCatalog.PROBLEM_ID,"cpp17","code");
            d.tick();d.tick();assertEquals(5,runs.get());assertEquals("AC",j.read(s.getSubmissionId()).verdict);
            assertEquals(TaskStatus.WAITING_JUDGE,r.find(s.getOwner(),s.getSubmissionId()).getStatus());
        }
        try(FileOjSubmissionRepository r=new FileOjSubmissionRepository(source);OjJudgeJournal j=new OjJudgeJournal(results);OjJudgeDispatcher d=new OjJudgeDispatcher(r,j,client(1000))){d.tick();assertEquals(5,runs.get());}
    }
    @Test void restartRecoversInterruptedWork() throws Exception {
        output="AUTO";try(FileOjSubmissionRepository r=new FileOjSubmissionRepository(root.resolve("submissions"));OjJudgeJournal j=new OjJudgeJournal(root.resolve("results"));OjJudgeDispatcher d=new OjJudgeDispatcher(r,j,client(1000))){
            Submission s=new OjSubmissionService(r,new FixedOjProblemCatalog()).submit(new Owner("student","course","class"),"one",FixedOjProblemCatalog.PROBLEM_ID,"cpp17","code");
            OjJudgeJournal.Entry e=new OjJudgeJournal.Entry();e.submissionId=s.getSubmissionId();e.state="RUNNING";e.attempts=1;j.write(e);
            d.tick();assertEquals(2,j.read(e.submissionId).attempts);assertEquals("DONE",j.read(e.submissionId).state);
        }
    }
    @Test void exhaustedRecoveryAndFailureAreTerminal() throws Exception {
        try(FileOjSubmissionRepository r=new FileOjSubmissionRepository(root.resolve("submissions"));OjJudgeJournal j=new OjJudgeJournal(root.resolve("results"));OjJudgeDispatcher d=new OjJudgeDispatcher(r,j,client(1000))){
            Submission s=new OjSubmissionService(r,new FixedOjProblemCatalog()).submit(new Owner("student","course","class"),"one",FixedOjProblemCatalog.PROBLEM_ID,"cpp17","code");
            OjJudgeJournal.Entry e=new OjJudgeJournal.Entry();e.submissionId=s.getSubmissionId();e.state="RUNNING";e.attempts=2;j.write(e);d.tick();assertEquals("FAILED",j.read(e.submissionId).state);assertEquals(0,runs.get());
        }
    }
    @Test void transportFailurePersistsWithoutFalseAcceptance() throws Exception {
        malformed=true;try(FileOjSubmissionRepository r=new FileOjSubmissionRepository(root.resolve("submissions"));OjJudgeJournal j=new OjJudgeJournal(root.resolve("results"));OjJudgeDispatcher d=new OjJudgeDispatcher(r,j,client(1000))){
            Submission s=new OjSubmissionService(r,new FixedOjProblemCatalog()).submit(new Owner("student","course","class"),"one",FixedOjProblemCatalog.PROBLEM_ID,"cpp17","code");d.tick();d.tick();assertEquals("SYSTEM_ERROR",j.read(s.getSubmissionId()).verdict);assertEquals("FAILED",j.read(s.getSubmissionId()).state);assertEquals(2,runs.get());
        }
    }
    @Test void concurrentDispatchIsSerialized() throws Exception {
        output="AUTO";try(FileOjSubmissionRepository r=new FileOjSubmissionRepository(root.resolve("submissions"));OjJudgeJournal j=new OjJudgeJournal(root.resolve("results"));OjJudgeDispatcher d=new OjJudgeDispatcher(r,j,client(1000))){
            new OjSubmissionService(r,new FixedOjProblemCatalog()).submit(new Owner("student","course","class"),"one",FixedOjProblemCatalog.PROBLEM_ID,"cpp17","code");
            ExecutorService pool=Executors.newFixedThreadPool(2);try{List<Future<?>> fs=new ArrayList<>();for(int i=0;i<2;i++)fs.add(pool.submit(()->{try{d.tick();}catch(IOException e){throw new UncheckedIOException(e);}}));for(Future<?> f:fs)f.get(5,TimeUnit.SECONDS);}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}assertEquals(5,runs.get());
        }
    }
    @Test void journalRejectsCorruptionAndExclusiveAccess() throws Exception {
        String id=String.join("",Collections.nCopies(64,"a"));Path dir=root.resolve("results");
        try(OjJudgeJournal j=new OjJudgeJournal(dir)) {
            assertThrows(Exception.class,()->new OjJudgeJournal(dir));OjJudgeJournal.Entry e=new OjJudgeJournal.Entry();e.submissionId=id;e.state="RUNNING";e.attempts=1;j.write(e);
            Path f=dir.resolve(id+".json");byte[] valid=Files.readAllBytes(f);Files.write(f,(new String(valid,"UTF-8")+" {}").getBytes("UTF-8"));assertThrows(IOException.class,()->j.read(id));
        }
        assertThrows(IOException.class,()->new OjJudgeJournal(dir));
    }
    OjTokenIssuer issuer(OjTokenIssuer.CredentialProof proof){return new OjTokenIssuer(proof,(RSAPublicKey)keys.getPublic(),(RSAPrivateKey)keys.getPrivate(),"isolated-issuer","oj");}
    OjStrictJwtIdentity identity(){return new OjStrictJwtIdentity((RSAPublicKey)keys.getPublic(),"isolated-issuer","oj");}
    @Test void issuerRequiresCredentialProofAndClearsPassword() throws Exception {
        char[] password="secret".toCharArray();String token=issuer((login,p)->{if(!login.equals("name")||!new String(p).equals("secret"))throw new SecurityException();return "database-user";}).login("name",password);
        assertArrayEquals(new char[6],password);MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer "+token);assertEquals("database-user",identity().userId(request));
        assertThrows(SecurityException.class,()->issuer((l,p)->null).login("name","bad".toCharArray()));
    }
    @Test void invalidSigningContractRejected(){assertThrows(IllegalArgumentException.class,()->new OjTokenIssuer((l,p)->"id",(RSAPublicKey)keys.getPublic(),null,"issuer","oj"));}
    @Test void signedHttpToProtocolToPrivateResult() throws Exception {
        output="AUTO";try(FileOjSubmissionRepository r=new FileOjSubmissionRepository(root.resolve("submissions"));OjJudgeJournal j=new OjJudgeJournal(root.resolve("results"));OjJudgeDispatcher d=new OjJudgeDispatcher(r,j,client(1000))){
            OjAuthorizationData data=new OjAuthorizationData(){public List<Student> studentsByUserId(String id){return Collections.singletonList(new Student(id,"1",true));}public boolean courseExists(String id){return id.equals("course");}public List<String> courseIdsByClassroom(String id){return id.equals("class")?Collections.singletonList("course"):Collections.emptyList();}public int membershipCount(String c,String u){return u.equals("student")?1:0;}};
            OjHttpController controller=new OjHttpController(new OjSubmissionService(r,new FixedOjProblemCatalog()),new OjProductionAccess(identity(),data));ReflectionTestUtils.setField(controller,"judgeJournal",j);
            MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).build();String token=issuer((l,p)->"student").login("name","secret".toCharArray());
            String body="{\"requestId\":\"http\",\"courseId\":\"course\",\"classroomId\":\"class\",\"problemId\":\"oj-fixture-add\",\"language\":\"cpp17\",\"sourceCode\":\"code\"}";
            String response=mvc.perform(post("/exam/oj/submissions").header("Authorization","Bearer "+token).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String id=json.readTree(response).path("submissionId").asText();d.tick();
            String detail=mvc.perform(get("/exam/oj/submissions/"+id).header("Authorization","Bearer "+token).param("courseId","course").param("classroomId","class")).andExpect(status().isOk()).andExpect(jsonPath("$.judgement.verdict").value("AC")).andReturn().getResponse().getContentAsString();
            assertFalse(detail.contains("expectedOutput"));assertFalse(detail.contains("checksum"));assertFalse(detail.contains("testCases"));
            mvc.perform(post("/exam/oj/submissions").contentType("application/json").content(body)).andExpect(status().isUnauthorized());assertEquals(1,r.snapshots().size());
        }
    }
    private static class SqlFixture {
        javax.sql.DataSource source=org.mockito.Mockito.mock(javax.sql.DataSource.class);
        java.sql.Connection connection=org.mockito.Mockito.mock(java.sql.Connection.class);
        java.sql.PreparedStatement statement=org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
        java.sql.ResultSet rows=org.mockito.Mockito.mock(java.sql.ResultSet.class);
        SqlFixture() throws Exception {
            org.mockito.Mockito.when(source.getConnection()).thenReturn(connection);
            org.mockito.Mockito.when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(statement);
            org.mockito.Mockito.when(statement.executeQuery()).thenReturn(rows);
        }
        OjJdbcAuthorizationData adapter(boolean student,boolean classroom){return new OjJdbcAuthorizationData(source,new OjJdbcAuthorizationData.Eligibility(){
            public boolean student(String u,String s){return student && "confirmed-user".equals(u) && "confirmed-student".equals(s);}
            public boolean classroom(String c,String t){return classroom && "confirmed-term".equals(t);}
        });}
    }
    @Test void jdbcStudentMappingUsesDatabaseUserIdAndExplicitPolicy() throws Exception {
        SqlFixture f=new SqlFixture();org.mockito.Mockito.when(f.rows.next()).thenReturn(true,false);
        org.mockito.Mockito.when(f.rows.getString("user_id")).thenReturn("database-user");org.mockito.Mockito.when(f.rows.getString("category")).thenReturn("1");
        org.mockito.Mockito.when(f.rows.getString("user_status")).thenReturn("confirmed-user");org.mockito.Mockito.when(f.rows.getString("student_status")).thenReturn("confirmed-student");
        List<OjAuthorizationData.Student> students=f.adapter(true,true).studentsByUserId("database-user");assertEquals(1,students.size());assertEquals("database-user",students.get(0).userId);assertTrue(students.get(0).eligible);
        org.mockito.Mockito.verify(f.connection).prepareStatement(org.mockito.ArgumentMatchers.contains("s.usersid=u.id"));org.mockito.Mockito.verify(f.statement).setString(1,"database-user");
    }
    @Test void jdbcUnknownStatusCannotBecomeEligible() throws Exception {
        SqlFixture f=new SqlFixture();org.mockito.Mockito.when(f.rows.next()).thenReturn(true,false);org.mockito.Mockito.when(f.rows.getString("user_id")).thenReturn("id");
        assertFalse(f.adapter(true,true).studentsByUserId("id").get(0).eligible);
    }
    @Test void jdbcClassroomTermPolicyDeniesUnknown() throws Exception {
        SqlFixture f=new SqlFixture();org.mockito.Mockito.when(f.rows.next()).thenReturn(true,false);org.mockito.Mockito.when(f.rows.getString("courseId")).thenReturn("course");
        assertNull(f.adapter(true,true).courseIdsByClassroom("class").get(0));org.mockito.Mockito.verify(f.statement).setString(1,"class");
    }
    @Test void jdbcMemberQueryBindsBothScopeAndUser() throws Exception {
        SqlFixture f=new SqlFixture();org.mockito.Mockito.when(f.rows.next()).thenReturn(true,false);org.mockito.Mockito.when(f.rows.getMetaData()).thenReturn(org.mockito.Mockito.mock(java.sql.ResultSetMetaData.class));
        org.mockito.Mockito.when(f.rows.getMetaData().getColumnCount()).thenReturn(1);org.mockito.Mockito.when(f.rows.getInt(1)).thenReturn(1);
        assertEquals(1,f.adapter(true,true).membershipCount("class","database-user"));org.mockito.Mockito.verify(f.statement).setString(1,"class");org.mockito.Mockito.verify(f.statement).setString(2,"database-user");
    }
    @Test void isolatedEnabledConfigurationWiresAndCloses() throws Exception {
        Path publicKey=root.resolve("public.pem");Files.write(publicKey,java.util.Base64.getEncoder().encode(keys.getPublic().getEncoded()));
        org.springframework.context.annotation.AnnotationConfigApplicationContext context=new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        Map<String,Object> properties=new HashMap<>();properties.put("oj.http.enabled","true");properties.put("oj.auth.enabled","true");properties.put("oj.judge.enabled","true");
        properties.put("oj.http.storage-directory",root.resolve("submissions").toString());properties.put("oj.judge.result-directory",root.resolve("results").toString());properties.put("oj.judge.endpoint","http://127.0.0.1:"+server.getAddress().getPort());
        properties.put("oj.auth.public-key-file",publicKey.toString());properties.put("oj.auth.issuer","isolated-issuer");properties.put("oj.auth.audience","oj");
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("isolated",properties));
        javax.sql.DataSource source=org.mockito.Mockito.mock(javax.sql.DataSource.class);
        context.registerBean(javax.sql.DataSource.class,()->source);
        context.registerBean(OjJdbcAuthorizationData.Eligibility.class,()->new OjJdbcAuthorizationData.Eligibility(){public boolean student(String a,String b){return false;}public boolean classroom(String c,String t){return false;}});
        context.register(OjAccessConfiguration.class,OjHttpConfiguration.class,OjJudgeConfiguration.class,OjHttpController.class);
        try {context.refresh();assertNotNull(context.getBean(OjHttpAccess.class));assertNotNull(context.getBean(OjJudgeDispatcher.class));org.mockito.Mockito.verifyNoInteractions(source);}
        finally{context.close();}
        try(FileOjSubmissionRepository r=new FileOjSubmissionRepository(root.resolve("submissions"));OjJudgeJournal j=new OjJudgeJournal(root.resolve("results"))){assertTrue(r.snapshots().isEmpty());}
    }
    @Test void unconfirmedEligibilityCannotAutoEnableAccess() throws Exception {
        org.springframework.context.annotation.AnnotationConfigApplicationContext context=new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("isolated",Collections.singletonMap("oj.auth.enabled","true")));
        context.register(OjAccessConfiguration.class);try{assertThrows(org.springframework.beans.BeansException.class,context::refresh);}finally{context.close();}
    }
    @Test void scheduledWorkerAutomaticallyJudgesSavedSubmission() throws Exception {
        output="AUTO";try(FileOjSubmissionRepository r=new FileOjSubmissionRepository(root.resolve("submissions"));OjJudgeJournal j=new OjJudgeJournal(root.resolve("results"));OjJudgeDispatcher d=new OjJudgeDispatcher(r,j,client(1000))){
            d.start();Submission s=new OjSubmissionService(r,new FixedOjProblemCatalog()).submit(new Owner("student","course","class"),"automatic",FixedOjProblemCatalog.PROBLEM_ID,"cpp17","code");
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);OjJudgeJournal.Entry e;
            do {e=j.read(s.getSubmissionId());if(e!=null && "DONE".equals(e.state))break;Thread.sleep(10);}while(System.nanoTime()<end);
            assertNotNull(e);assertEquals("DONE",e.state);assertEquals("AC",e.verdict);assertFalse(d.isUnhealthy());
        }
    }
    @Test void failedTransportCanRecoverOnNextPass() throws Exception {
        malformed=true;try(FileOjSubmissionRepository r=new FileOjSubmissionRepository(root.resolve("submissions"));OjJudgeJournal j=new OjJudgeJournal(root.resolve("results"));OjJudgeDispatcher d=new OjJudgeDispatcher(r,j,client(1000))){
            Submission s=new OjSubmissionService(r,new FixedOjProblemCatalog()).submit(new Owner("student","course","class"),"retry",FixedOjProblemCatalog.PROBLEM_ID,"cpp17","code");
            d.tick();assertEquals("RETRY",j.read(s.getSubmissionId()).state);malformed=false;output="AUTO";d.tick();assertEquals("AC",j.read(s.getSubmissionId()).verdict);assertEquals(2,j.read(s.getSubmissionId()).attempts);
        }
    }
    private Map<String,JsonNode> override(String name,String value) throws Exception {
        return Collections.singletonMap(name,json.readTree(value));
    }
    private void overrides(boolean compiler,Map<String,JsonNode> values) {
        if(compiler)compileOverrides=values;else runtimeOverrides=values;
    }
    @Test void rejectsExitStatusOutsideIntRangeInBothPhases() throws Exception {
        output="AUTO";Submission s=record();
        for(boolean compiler:new boolean[]{true,false}) {
            for(String value:new String[]{"4294967296","-4294967296","2147483648","-2147483649",
                    "18446744073709551616","-18446744073709551616"}) {
                overrides(compiler,override("exitStatus",value));
                assertThrows(IOException.class,()->client(1000).judge(s),"Exit status must not narrow: "+value);
            }
            overrides(compiler,Collections.emptyMap());
        }
    }
    @Test void rejectsNonIntegralAndMissingExitStatusInBothPhases() throws Exception {
        output="AUTO";Submission s=record();
        for(boolean compiler:new boolean[]{true,false}) {
            for(String value:new String[]{"null","\"0\"","true","0.0","[]","{}"}) {
                overrides(compiler,override("exitStatus",value));
                assertThrows(IOException.class,()->client(1000).judge(s),"Invalid exit status type: "+value);
            }
            overrides(compiler,Collections.singletonMap("exitStatus",com.fasterxml.jackson.databind.node.MissingNode.getInstance()));
            assertThrows(IOException.class,()->client(1000).judge(s));
            overrides(compiler,Collections.emptyMap());
        }
    }
    @Test void exactIntegerBoundariesNeverWrapToAcceptance() throws Exception {
        output="AUTO";Submission s=record();
        assertEquals(Verdict.AC,client(1000).judge(s).verdict);
        for(int code:new int[]{Integer.MIN_VALUE,-1,1,Integer.MAX_VALUE}) {
            compile="Nonzero Exit Status";compileOverrides=override("exitStatus",Integer.toString(code));
            assertEquals(Verdict.CE,client(1000).judge(s).verdict);
            compile="Accepted";compileOverrides=Collections.emptyMap();
            runtime="Nonzero Exit Status";runtimeOverrides=override("exitStatus",Integer.toString(code));
            assertEquals(Verdict.RE,client(1000).judge(s).verdict);
            runtime="Accepted";runtimeOverrides=Collections.emptyMap();
        }
    }
    @Test @Timeout(30) void rejectsLossyAndNegativeResourceMetadataInBothPhases() throws Exception {
        output="AUTO";Submission s=record();
        for(boolean compiler:new boolean[]{true,false}) {
            for(String field:new String[]{"time","memory","runTime","procPeak"}) {
                for(String value:new String[]{"9223372036854775808","18446744073709551616","-1","1.5","\"1\"","true","null","[]"}) {
                    overrides(compiler,override(field,value));
                    assertThrows(IOException.class,()->client(1000).judge(s),"Invalid "+field+": "+value);
                }
            }
            overrides(compiler,Collections.emptyMap());
        }
    }
    @Test void acceptsExactNonnegativeLongResourceBoundaries() throws Exception {
        output="AUTO";Submission s=record();
        for(long value:new long[]{0L,Long.MAX_VALUE}) {
            Map<String,JsonNode> values=new HashMap<>();
            for(String field:new String[]{"time","memory","runTime","procPeak"})values.put(field,json.getNodeFactory().numberNode(value));
            compileOverrides=values;runtimeOverrides=values;
            GoJudgeClient.Result result=client(1000).judge(s);
            assertEquals(Verdict.AC,result.verdict);assertEquals(value,result.timeNs);assertEquals(value,result.memoryBytes);
        }
    }
    @Test void unknownAndInfrastructureCompileStatusesNeverBecomeCe() throws Exception {
        Submission s=record();compileOverrides=override("exitStatus","1");
        for(String state:new String[]{"UNRECOGNIZED_INFRA_FAILURE","Internal Error","File Error","Time Limit Exceeded",
                "Memory Limit Exceeded","Output Limit Exceeded","Signalled","Dangerous Syscall"}) {
            compile=state;assertEquals(Verdict.SYSTEM_ERROR,client(1000).judge(s).verdict,state);
        }
        assertEquals(8,runs.get());
    }
    @Test void contradictoryAndMalformedStatusesAreTechnicalFailures() throws Exception {
        Submission s=record();
        for(boolean compiler:new boolean[]{true,false}) {
            overrides(compiler,override("exitStatus","1"));
            assertEquals(Verdict.SYSTEM_ERROR,client(1000).judge(s).verdict);
            overrides(compiler,Collections.emptyMap());
            if(compiler)compile="Nonzero Exit Status";else runtime="Nonzero Exit Status";
            overrides(compiler,override("exitStatus","0"));
            assertEquals(Verdict.SYSTEM_ERROR,client(1000).judge(s).verdict);
            if(compiler)compile="Accepted";else runtime="Accepted";
            for(String value:new String[]{"null","1","true","\"\""}) {
                overrides(compiler,override("status",value));
                assertThrows(IOException.class,()->client(1000).judge(s));
            }
            overrides(compiler,Collections.emptyMap());
        }
    }
    @Test void explicitExecutorErrorsCannotBeHiddenBySuccessOrNonzeroExit() throws Exception {
        Submission s=record();
        for(boolean compiler:new boolean[]{true,false}) {
            for(Map<String,JsonNode> fields:Arrays.asList(override("error","\"executor failed\""),
                    override("fileError","[{\"name\":\"main\"}]"))) {
                overrides(compiler,fields);assertEquals(Verdict.SYSTEM_ERROR,client(1000).judge(s).verdict);
                if(compiler)compile="Nonzero Exit Status";else runtime="Nonzero Exit Status";
                assertEquals(Verdict.SYSTEM_ERROR,client(1000).judge(s).verdict);
                if(compiler)compile="Accepted";else runtime="Accepted";
            }
            overrides(compiler,Collections.emptyMap());
        }
    }
    @Test void normalAndAbove64KiBArtifactsUseIndependentCacheBudget() throws Exception {
        output="AUTO";Submission s=record();
        for(long size:new long[]{32768,96L*1024,GoJudgeClient.DEFAULT_COMPILE_ARTIFACT_LIMIT_BYTES}) {
            artifactBytes=size;assertEquals(Verdict.AC,client(1000).judge(s).verdict);
        }
        for(JsonNode cmd:commands) {
            assertEquals(65536,cmd.path("files").get(1).path("max").longValue());
            assertEquals(65536,cmd.path("files").get(2).path("max").longValue());
            if(cmd.has("copyOutCached")) {
                assertEquals("main",cmd.path("copyOutCached").get(0).textValue());
                assertEquals(GoJudgeClient.DEFAULT_COMPILE_ARTIFACT_LIMIT_BYTES,cmd.path("copyOutMax").longValue());
                assertFalse(cmd.path("copyOutTruncate").booleanValue());
            } else {
                assertEquals(65536,cmd.path("copyOutMax").longValue());
                assertEquals(128L*1024*1024,cmd.path("memoryLimit").longValue());
                assertEquals(1000000000L,cmd.path("cpuLimit").longValue());
                assertEquals(3000000000L,cmd.path("clockLimit").longValue());
            }
        }
        assertEquals(15,runs.get());assertEquals(3,deletes.get());
    }
    @Test void artifactBudgetIsBoundedAndConfigurable() throws Exception {
        URI endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        for(long size:new long[]{Long.MIN_VALUE,-1,0,65535,GoJudgeClient.MAX_COMPILE_ARTIFACT_LIMIT_BYTES+1,Long.MAX_VALUE})
            assertThrows(IllegalArgumentException.class,()->new GoJudgeClient(endpoint,1000,"",size));
        assertNotNull(new GoJudgeClient(endpoint,1000,"",65536));
        assertNotNull(new GoJudgeClient(endpoint,1000,"",GoJudgeClient.MAX_COMPILE_ARTIFACT_LIMIT_BYTES));
        GoJudgeClient configured=new OjJudgeConfiguration().ojGoJudgeClient(endpoint.toString(),"",128L*1024);
        output="AUTO";Submission s=record();artifactBytes=96L*1024;
        assertEquals(Verdict.AC,configured.judge(s).verdict);
        assertEquals(128L*1024,commands.get(0).path("copyOutMax").longValue());
        artifactBytes=128L*1024+1;
        assertEquals(Verdict.SYSTEM_ERROR,configured.judge(s).verdict);
        assertEquals(6,runs.get());
    }
    @Test void artifactOverflowAndMissingCacheIdAreTechnicalFailures() throws Exception {
        Submission s=record();artifactBytes=GoJudgeClient.DEFAULT_COMPILE_ARTIFACT_LIMIT_BYTES+1;
        assertEquals(Verdict.SYSTEM_ERROR,client(1000).judge(s).verdict);assertEquals(1,runs.get());
        artifactBytes=32768;compileOverrides=override("fileIds","{}");
        assertThrows(IOException.class,()->client(1000).judge(s));assertEquals(2,runs.get());
    }
    @Test void isolatedArtifactPropertyControlsConfiguredClient() throws Exception {
        output="AUTO";artifactBytes=96L*1024;
        Path source=root.resolve("configured-submissions"),results=root.resolve("configured-results");
        try(FileOjSubmissionRepository repository=new FileOjSubmissionRepository(source)) {
            org.springframework.context.annotation.AnnotationConfigApplicationContext context=new org.springframework.context.annotation.AnnotationConfigApplicationContext();
            Map<String,Object> properties=new HashMap<>();properties.put("oj.judge.enabled","true");
            properties.put("oj.judge.endpoint","http://127.0.0.1:"+server.getAddress().getPort());
            properties.put("oj.http.storage-directory",source.toString());properties.put("oj.judge.result-directory",results.toString());
            properties.put("oj.judge.compile-artifact-limit-bytes","131072");
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("isolated",properties));
            context.registerBean(FileOjSubmissionRepository.class,()->repository);context.register(OjJudgeConfiguration.class);
            try {
                context.refresh();assertEquals(Verdict.AC,context.getBean(GoJudgeClient.class).judge(record()).verdict);
                assertEquals(131072,commands.get(0).path("copyOutMax").longValue());
                assertFalse(context.containsBean("ojHttpController"));
            } finally {context.close();}
        }
    }
    private void technicalFailureRemainsBoundedAcrossRestart() throws Exception {
        Path source=root.resolve("p2-submissions"),results=root.resolve("p2-results");
        String id;
        try(FileOjSubmissionRepository r=new FileOjSubmissionRepository(source);OjJudgeJournal j=new OjJudgeJournal(results);OjJudgeDispatcher d=new OjJudgeDispatcher(r,j,client(1000))) {
            Submission s=new OjSubmissionService(r,new FixedOjProblemCatalog()).submit(new Owner("student","course","class"),"p2",FixedOjProblemCatalog.PROBLEM_ID,"cpp17","code");
            id=s.getSubmissionId();d.tick();OjJudgeJournal.Entry entry=j.read(id);
            assertEquals("RETRY",entry.state);assertEquals("SYSTEM_ERROR",entry.verdict);assertEquals(1,entry.attempts);
        }
        int before=runs.get();
        try(FileOjSubmissionRepository r=new FileOjSubmissionRepository(source);OjJudgeJournal j=new OjJudgeJournal(results);OjJudgeDispatcher d=new OjJudgeDispatcher(r,j,client(1000))) {
            d.tick();OjJudgeJournal.Entry entry=j.read(id);
            assertEquals("FAILED",entry.state);assertEquals("SYSTEM_ERROR",entry.verdict);assertEquals(2,entry.attempts);
            byte[] terminal=Files.readAllBytes(results.resolve(id+".json"));int completed=runs.get();
            assertTrue(completed>before);d.tick();assertEquals(completed,runs.get());
            assertArrayEquals(terminal,Files.readAllBytes(results.resolve(id+".json")));
        }
        int completed=runs.get();
        try(FileOjSubmissionRepository r=new FileOjSubmissionRepository(source);OjJudgeJournal j=new OjJudgeJournal(results);OjJudgeDispatcher d=new OjJudgeDispatcher(r,j,client(1000))) {
            d.tick();assertEquals(completed,runs.get());assertEquals("FAILED",j.read(id).state);assertEquals(2,j.read(id).attempts);
        }
    }
    @Test void overflowingExitCodePersistsAsBoundedTechnicalFailure() throws Exception {
        output="AUTO";runtimeOverrides=override("exitStatus","4294967296");technicalFailureRemainsBoundedAcrossRestart();
    }
    @Test void unknownCompilerFailurePersistsAsBoundedTechnicalFailure() throws Exception {
        compile="UNRECOGNIZED_INFRA_FAILURE";compileOverrides=override("exitStatus","1");technicalFailureRemainsBoundedAcrossRestart();
    }
    @Test void cacheOverflowPersistsAsBoundedTechnicalFailure() throws Exception {
        artifactBytes=GoJudgeClient.DEFAULT_COMPILE_ARTIFACT_LIMIT_BYTES+1;technicalFailureRemainsBoundedAcrossRestart();
    }
    @Test void unknownCompilerFailureCanRecoverWithoutRepeatingTerminalWork() throws Exception {
        compile="UNRECOGNIZED_INFRA_FAILURE";compileOverrides=override("exitStatus","1");
        try(FileOjSubmissionRepository r=new FileOjSubmissionRepository(root.resolve("submissions"));OjJudgeJournal j=new OjJudgeJournal(root.resolve("results"));OjJudgeDispatcher d=new OjJudgeDispatcher(r,j,client(1000))) {
            Submission s=new OjSubmissionService(r,new FixedOjProblemCatalog()).submit(new Owner("student","course","class"),"p2",FixedOjProblemCatalog.PROBLEM_ID,"cpp17","code");
            d.tick();assertEquals("RETRY",j.read(s.getSubmissionId()).state);
            compile="Accepted";compileOverrides=Collections.emptyMap();output="AUTO";
            d.tick();OjJudgeJournal.Entry entry=j.read(s.getSubmissionId());assertEquals("DONE",entry.state);assertEquals("AC",entry.verdict);assertEquals(2,entry.attempts);
            d.tick();assertEquals(6,runs.get());
        }
    }
    @Test void genuineCompilerExitFailureIsTerminalCeWithoutRetry() throws Exception {
        compile="Nonzero Exit Status";
        try(FileOjSubmissionRepository r=new FileOjSubmissionRepository(root.resolve("submissions"));OjJudgeJournal j=new OjJudgeJournal(root.resolve("results"));OjJudgeDispatcher d=new OjJudgeDispatcher(r,j,client(1000))) {
            Submission s=new OjSubmissionService(r,new FixedOjProblemCatalog()).submit(new Owner("student","course","class"),"p2",FixedOjProblemCatalog.PROBLEM_ID,"cpp17","code");
            d.tick();OjJudgeJournal.Entry entry=j.read(s.getSubmissionId());assertEquals("DONE",entry.state);assertEquals("CE",entry.verdict);assertEquals(1,entry.attempts);
            d.tick();assertEquals(1,runs.get());
        }
    }
}
