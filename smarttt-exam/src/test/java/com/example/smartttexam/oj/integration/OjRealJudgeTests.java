package com.example.smartttexam.oj.integration;

import java.net.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real sandbox opt-in only. Expected verdict mismatches FAIL; no simulated fallback.
 * Inherits the 8 actual-DB/HTTP authorization cases with real judge wiring.
 */
@Tag("real-go-judge")
public class OjRealJudgeTests extends OjRealMysqlTests {
    static URI endpoint;
    static com.fasterxml.jackson.databind.JsonNode environment;
    @BeforeAll static void sandbox() {
        assertEquals("YES_DEDICATED_SANDBOX",System.getenv("OJ_LAB_JUDGE_ISOLATED"));
        endpoint=URI.create(Objects.requireNonNull(System.getenv("OJ_LAB_JUDGE_ENDPOINT")));
        assertEquals("http",endpoint.getScheme());assertEquals("127.0.0.1",endpoint.getHost());
        assertTrue(endpoint.getPort()>1023);assertNull(endpoint.getUserInfo());
        assertNull(endpoint.getQuery());assertNull(endpoint.getFragment());assertTrue(endpoint.getPath().isEmpty());
        String bearer=System.getenv("OJ_LAB_JUDGE_TOKEN");assertNotNull(bearer);assertTrue(bearer.matches("[a-f0-9]{64}"));
        try {
            environment=new com.fasterxml.jackson.databind.ObjectMapper().readTree(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(Objects.requireNonNull(System.getenv("OJ_LAB_SANDBOX_EVIDENCE")))));
            assertEquals("smarttt-oj-preflight-v1",environment.path("schema").asText());
            assertEquals("v1.9.10",environment.path("version").asText());assertTrue(environment.path("auth_required").asBoolean());
        }catch(IOException e){throw new IllegalStateException("Real sandbox preflight evidence required",e);}
    }
    @Override @BeforeEach void start() throws Exception {
        super.start();server.close();server=null;server=new OjIsolatedServer(reader,keys,root,endpoint);
    }
    Reply judged(String source,String verdict) throws Exception {
        Map<String,Object> body=submission("real-sandbox-"+UUID.randomUUID());body.put("sourceCode",source);
        Reply first=call("POST","/api/exam/oj/submissions",token,body);assertEquals(200,first.code);
        Reply response=terminal(first.body.path("submissionId").asText());
        assertEquals("DONE",response.body.path("judgement").path("state").asText(),"Technical failures are not student verdicts");
        assertEquals(verdict,response.body.path("judgement").path("verdict").asText());
        for(String secret:Arrays.asList("testCases","expectedOutput","fileIds","stdout","stderr","checksum"))assertFalse(response.raw.contains("\""+secret+"\""));
        return response;
    }
    Reply terminal(String id) throws Exception {
        String path="/api/exam/oj/submissions/"+id+"?courseId=lab-course&classroomId=lab-class";
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(150);
        Reply response;
        do {
            response=call("GET",path,token,null);assertEquals(200,response.code);
            String state=response.body.path("judgement").path("state").asText();
            if(state.equals("DONE")||state.equals("FAILED"))break;
            assertTrue(System.nanoTime()<until,"Real sandbox judgement timed out");Thread.sleep(150);
        }while(true);
        return response;
    }
    @Test void realCpp17AcSurvivesRestartAndHistoryQuery() throws Exception {
        Reply accepted=judged((String)submission("unused").get("sourceCode"),"AC");
        server.close();server=null;server=new OjIsolatedServer(reader,keys,root,endpoint);
        Reply history=call("GET",HISTORY,token,null);assertEquals(200,history.code);assertEquals(1,history.body.size());
        assertEquals(accepted.body.path("submissionId"),history.body.get(0).path("submissionId"));
        assertEquals("AC",history.body.get(0).path("judgement").path("verdict").asText());
    }
    @Test void realCpp17WrongAnswer() throws Exception {judged("#include <iostream>\nint main(){std::cout<<42;}","WA");}
    @Test void realCasesBeyondTheFirstAreActuallyEvaluated() throws Exception {
        judged("#include <iostream>\nint main(){long long a,b;std::cin>>a>>b;std::cout<<((a==2&&b==3)?5:42);}","WA");
    }
    @Test void realCpp17CompilationError() throws Exception {judged("int main( { syntax error","CE");}
    @Test void realCpp17RuntimeError() throws Exception {judged("int main(){return 7;}","RE");}
    @Test void realCpp17CpuLimit() throws Exception {
        Reply r=judged("int main(){volatile unsigned long long x=0;while(true){++x;}}","TLE");
        long cpu=r.body.path("judgement").path("timeNs").asLong();assertTrue(cpu>=800000000L && cpu<2000000000L,"Actual CPU budget must terminate before the 3s wall limit");
    }
    @Test void realCpp17MemoryLimit() throws Exception {
        judged("#include <cstdlib>\nint main(){for(;;){volatile char* p=(char*)malloc(1048576);if(!p)return 8;for(int i=0;i<1048576;i+=4096)p[i]=1;}}","MLE");
    }
    @Test void realCpp17OutputLimit() throws Exception {judged("#include <cstdio>\nint main(){for(;;)puts(\"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\");}","OLE");}
    @Test void realExecutableAbove64KiBCanBeCachedAndRun() throws Exception {
        String source="#include <iostream>\nvolatile unsigned char payload[131072]={1};int main(){long long a,b;std::cin>>a>>b;std::cout<<a+b+(payload[0]-1);}";
        // Independent protocol probe measures the REAL cached binary, not a fixture size field.
        Map<String,Object> command=new HashMap<>();
        command.put("args",Arrays.asList("/usr/bin/g++","main.cpp","-std=c++17","-O2","-o","main"));
        command.put("env",Collections.singletonList("PATH=/usr/bin:/bin"));
        command.put("files",Arrays.asList(Collections.singletonMap("content",""),collector("stdout"),collector("stderr")));
        command.put("copyIn",Collections.singletonMap("main.cpp",Collections.singletonMap("content",source)));
        command.put("copyOutCached",Collections.singletonList("main"));command.put("copyOutMax",16777216);
        command.put("copyOutTruncate",false);command.put("cpuLimit",10000000000L);command.put("clockLimit",20000000000L);
        command.put("memoryLimit",536870912);command.put("procLimit",32);
        com.fasterxml.jackson.databind.JsonNode reply=json.readTree(judgeRequest("POST","/run",json.writeValueAsBytes(Collections.singletonMap("cmd",Collections.singletonList(command))))).get(0);
        assertEquals("Accepted",reply.path("status").asText());assertEquals(0,reply.path("exitStatus").asInt(-1));
        String id=reply.path("fileIds").path("main").asText();assertTrue(id.matches("[A-Za-z0-9_-]{1,200}"));
        try {int size=judgeRequest("GET","/file/"+id,null).length;assertTrue(size>65536 && size<=16777216,"Actual executable cache size");}
        finally {judgeRequest("DELETE","/file/"+id,null);}
        judged(source,"AC");
    }
    Map<String,Object> collector(String name){Map<String,Object> c=new HashMap<>();c.put("name",name);c.put("max",65536);return c;}
    @Test void realVersionCgroupControllersAndApiAuthentication() throws Exception {
        com.fasterxml.jackson.databind.JsonNode v=json.readTree(judgeRequest("GET","/version",null));
        assertEquals("v1.9.10",v.path("buildVersion").asText());assertEquals("linux",v.path("os").asText());
        com.fasterxml.jackson.databind.JsonNode cfg=json.readTree(judgeRequest("GET","/config",null)).path("runnerConfig");
        assertEquals(2,cfg.path("cgroupType").asInt());
        Set<String> controllers=new HashSet<>();cfg.path("cgroupControllers").forEach(n->controllers.add(n.asText()));
        assertTrue(controllers.containsAll(Arrays.asList("cpu","memory","pids")));
        HttpURLConnection c=(HttpURLConnection)endpoint.resolve("/file").toURL().openConnection();
        c.setConnectTimeout(3000);c.setReadTimeout(3000);c.setInstanceFollowRedirects(false);
        try{assertEquals(401,c.getResponseCode());}finally{c.disconnect();}
    }
    @Test void realCacheOverflowUsesBoundedTechnicalFailureAndSurvivesRestart() throws Exception {
        server.close();server=null;server=new OjIsolatedServer(reader,keys,root,endpoint,65536);
        String source="#include <iostream>\nvolatile unsigned char data[131072]={1};int main(){std::cout<<data[0];}";
        Map<String,Object> body=submission("actual-cache-overflow");body.put("sourceCode",source);
        Reply saved=call("POST","/api/exam/oj/submissions",token,body);assertEquals(200,saved.code);
        assertTechnicalFailure(saved.body.path("submissionId").asText());
    }
    @Test void realUnavailableExecutorUsesBoundedRetriesAndPersistsFailure() throws Exception {
        judged((String)submission("unused").get("sourceCode"),"AC"); // first prove the real service works
        int unavailable;try(ServerSocket socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){unavailable=socket.getLocalPort();}
        server.close();server=null;server=new OjIsolatedServer(reader,keys,root,URI.create("http://127.0.0.1:"+unavailable));
        Reply saved=call("POST","/api/exam/oj/submissions",token,submission("executor-unavailable"));assertEquals(200,saved.code);
        assertTechnicalFailure(saved.body.path("submissionId").asText());
    }
    void assertTechnicalFailure(String id) throws Exception {
        Reply failed=terminal(id);assertEquals("FAILED",failed.body.path("judgement").path("state").asText());
        assertEquals("SYSTEM_ERROR",failed.body.path("judgement").path("verdict").asText());
        java.nio.file.Path file=root.resolve("results").resolve(id+".json");
        byte[] before=java.nio.file.Files.readAllBytes(file);assertEquals(2,json.readTree(before).path("attempts").asInt());
        server.close();server=null;server=new OjIsolatedServer(reader,keys,root,endpoint);Thread.sleep(1500);
        assertArrayEquals(before,java.nio.file.Files.readAllBytes(file));assertEquals("FAILED",terminal(id).body.path("judgement").path("state").asText());
    }
    com.fasterxml.jackson.databind.JsonNode probe(String source,long cpu,long wall,int processes) throws Exception {
        Map<String,Object> compile=new HashMap<>();
        compile.put("args",Arrays.asList("/usr/bin/g++","probe.cpp","-std=c++17","-O2","-o","probe"));
        compile.put("env",Collections.singletonList("PATH=/usr/bin:/bin"));
        compile.put("files",Arrays.asList(Collections.singletonMap("content",""),collector("stdout"),collector("stderr")));
        compile.put("copyIn",Collections.singletonMap("probe.cpp",Collections.singletonMap("content",source)));
        compile.put("copyOutCached",Collections.singletonList("probe"));compile.put("copyOutMax",16777216);compile.put("copyOutTruncate",false);
        compile.put("cpuLimit",10000000000L);compile.put("clockLimit",20000000000L);compile.put("memoryLimit",536870912);compile.put("procLimit",32);
        com.fasterxml.jackson.databind.JsonNode compiled=json.readTree(judgeRequest("POST","/run",json.writeValueAsBytes(Collections.singletonMap("cmd",Collections.singletonList(compile))))).get(0);
        assertEquals("Accepted",compiled.path("status").asText());assertTrue(compiled.path("exitStatus").canConvertToInt());assertEquals(0,compiled.path("exitStatus").intValue());
        String id=compiled.path("fileIds").path("probe").asText();assertTrue(id.matches("[A-Za-z0-9_-]{1,200}"));
        try {
            Map<String,Object> run=new HashMap<>(compile);run.put("args",Collections.singletonList("probe"));
            run.put("copyIn",Collections.singletonMap("probe",Collections.singletonMap("fileId",id)));run.remove("copyOutCached");
            run.put("cpuLimit",cpu);run.put("clockLimit",wall);run.put("memoryLimit",134217728);run.put("procLimit",processes);
            return json.readTree(judgeRequest("POST","/run",json.writeValueAsBytes(Collections.singletonMap("cmd",Collections.singletonList(run))))).get(0);
        }finally{judgeRequest("DELETE","/file/"+id,null);}
    }
    @Test void realWallClockLimitTerminatesSleepingProgram() throws Exception {
        com.fasterxml.jackson.databind.JsonNode r=probe("#include <unistd.h>\nint main(){sleep(10);}",2000000000L,200000000L,8);
        assertEquals("Time Limit Exceeded",r.path("status").asText());assertTrue(r.path("runTime").asLong()>=200000000L);
        assertTrue(r.path("runTime").asLong()<3000000000L);assertTrue(r.path("time").asLong()<1000000000L);
    }
    @Test void realProcessLimitRejectsBoundedForkAttempts() throws Exception {
        String source="#include <unistd.h>\n#include <sys/wait.h>\n#include <signal.h>\n#include <cerrno>\n#include <cstdio>\nint main(){pid_t children[64];int n=0,e=0;for(;n<64;n++){pid_t p=fork();if(p<0){e=errno;break;}if(p==0){sleep(10);_exit(0);}children[n]=p;}for(int i=0;i<n;i++)kill(children[i],SIGKILL);for(int i=0;i<n;i++)waitpid(children[i],0,0);if(e!=EAGAIN||n<1||n>=8)return 5;puts(\"PIDS_LIMIT_OK\");}";
        com.fasterxml.jackson.databind.JsonNode r=probe(source,2000000000L,3000000000L,8);
        assertEquals("Accepted",r.path("status").asText());assertEquals(0,r.path("exitStatus").asInt(-1));
        assertEquals("PIDS_LIMIT_OK",r.path("files").path("stdout").asText().trim());
        assertTrue(r.path("procPeak").asInt()>0 && r.path("procPeak").asInt()<=8);
    }
    @Test void realNamespaceNetworkAndFilesystemIsolation() throws Exception {
        String source="#include <unistd.h>\n#include <sys/socket.h>\n#include <arpa/inet.h>\n#include <cerrno>\n#include <cstdio>\n#include <cstring>\nint main(){if(getuid()==0||access(\"/opt/oj-host-canary\",F_OK)==0)return 4;const char* names[]={\"net\",\"mnt\",\"pid\",\"uts\",\"ipc\"};for(auto n:names){char p[80],b[128]={0};snprintf(p,80,\"/proc/self/ns/%s\",n);if(readlink(p,b,127)<0)return 5;printf(\"%s=%s\\n\",n,b);}FILE* f=fopen(\"/proc/self/status\",\"r\");if(!f)return 6;char line[256];bool filtered=false;while(fgets(line,256,f))if(strncmp(line,\"Seccomp:\",8)==0&&strchr(line,'2'))filtered=true;fclose(f);if(!filtered)return 7;int s=socket(AF_INET,SOCK_STREAM|SOCK_NONBLOCK,0);if(s<0)return 8;sockaddr_in a={};a.sin_family=AF_INET;a.sin_port=htons(9);inet_pton(AF_INET,\"192.0.2.1\",&a.sin_addr);int r=connect(s,(sockaddr*)&a,sizeof(a)),e=errno;close(s);if(r==0||e!=ENETUNREACH)return 9;puts(\"ISOLATION_OK\");}";
        com.fasterxml.jackson.databind.JsonNode r=probe(source,1000000000L,2000000000L,8);
        assertEquals("Accepted",r.path("status").asText());assertEquals(0,r.path("exitStatus").asInt(-1));
        String output=r.path("files").path("stdout").asText();assertTrue(output.contains("ISOLATION_OK"));
        Map<String,String> values=new HashMap<>();for(String line:output.split("\\n")){int eq=line.indexOf('=');if(eq>0)values.put(line.substring(0,eq),line.substring(eq+1).trim());}
        for(String ns:Arrays.asList("net","mnt","pid","uts","ipc")){
            String outer=environment.path("outerNamespaces").path(ns).asText();assertFalse(outer.isEmpty());assertNotNull(values.get(ns));assertNotEquals(outer,values.get(ns));
        }
    }
    @Test void realSeccompKillsForbiddenSyscall() throws Exception {
        com.fasterxml.jackson.databind.JsonNode r=probe("#include <sys/ptrace.h>\nint main(){ptrace(PTRACE_TRACEME,0,0,0);return 0;}",1000000000L,2000000000L,8);
        assertEquals("Dangerous Syscall",r.path("status").asText());
    }
    byte[] judgeRequest(String method,String path,byte[] body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)endpoint.resolve(path).toURL().openConnection();
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(3000);c.setReadTimeout(30000);c.setRequestMethod(method);
        String bearer=System.getenv("OJ_LAB_JUDGE_TOKEN");if(bearer!=null&&!bearer.isEmpty())c.setRequestProperty("Authorization","Bearer "+bearer);
        try {
            if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream o=c.getOutputStream()){o.write(body);}}
            assertTrue(c.getResponseCode()>=200 && c.getResponseCode()<300);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){assertTrue(out.size()+n<=16777216);out.write(b,0,n);}return out.toByteArray();
            }
        }finally{c.disconnect();}
    }
}
