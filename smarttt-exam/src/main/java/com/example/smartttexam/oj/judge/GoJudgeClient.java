package com.example.smartttexam.oj.judge;

import com.example.smartttexam.oj.OjModels.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** HTTP protocol only: all untrusted execution happens in the separately operated sandbox. */
public final class GoJudgeClient {
    public static final long DEFAULT_COMPILE_ARTIFACT_LIMIT_BYTES=16L*1024*1024;
    public static final long MAX_COMPILE_ARTIFACT_LIMIT_BYTES=64L*1024*1024;
    private final URI endpoint;
    private final int timeout;
    private final String bearer;
    private final long compileArtifactLimitBytes;
    private final ThreadLocal<Long> deadline=new ThreadLocal<>();
    private final ObjectMapper json=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public GoJudgeClient(URI endpoint,int timeoutMillis) { this(endpoint,timeoutMillis,""); }
    public GoJudgeClient(URI endpoint,int timeoutMillis,String bearer) {
        this(endpoint,timeoutMillis,bearer,DEFAULT_COMPILE_ARTIFACT_LIMIT_BYTES);
    }
    public GoJudgeClient(URI endpoint,int timeoutMillis,String bearer,long compileArtifactLimitBytes) {
        if(endpoint==null || !Arrays.asList("http","https").contains(endpoint.getScheme())
                || endpoint.getHost()==null || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null
                || endpoint.getFragment()!=null || !(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/"))
                || timeoutMillis<100 || timeoutMillis>120000) throw new IllegalArgumentException("Invalid judge endpoint");
        if(bearer==null || bearer.contains("\r") || bearer.contains("\n"))throw new IllegalArgumentException("Invalid judge credentials");
        if(compileArtifactLimitBytes<65536 || compileArtifactLimitBytes>MAX_COMPILE_ARTIFACT_LIMIT_BYTES)
            throw new IllegalArgumentException("Compile artifact limit must be between 64 KiB and 64 MiB");
        this.endpoint=endpoint;this.timeout=timeoutMillis;this.bearer=bearer;
        this.compileArtifactLimitBytes=compileArtifactLimitBytes;
    }
    public static final class Result {
        public Verdict verdict;
        public long timeNs,memoryBytes;
        public Result() { }
        public Result(Verdict v){verdict=v;}
    }
    public Result judge(Submission submission) throws IOException {
        deadline.set(System.nanoTime()+TimeUnit.SECONDS.toNanos(60));
        try { return execute(submission); } finally { deadline.remove(); }
    }
    private Result execute(Submission submission) throws IOException {
        Problem p=submission.getProblem();
        if(!"cpp17".equals(submission.getLanguage()) || p.getTestCases().isEmpty() || p.getTestCases().size()>100
                || p.getCpuTimeLimitMs()<1 || p.getCpuTimeLimitMs()>60000 || p.getWallTimeLimitMs()<1
                || p.getWallTimeLimitMs()>60000 || p.getMemoryLimitBytes()<1 || p.getMemoryLimitBytes()>1073741824L
                || p.getOutputLimitBytes()<1 || p.getOutputLimitBytes()>1048576)
            throw new IOException("Invalid judge problem limits");
        ObjectNode compile=command(Arrays.asList("/usr/bin/g++","main.cpp","-std=c++17","-O2","-o","main"),"",10000000000L,20000000000L,536870912,65536);
        compile.putObject("copyIn").putObject("main.cpp").put("content",submission.getSourceCode());
        compile.putArray("copyOutCached").add("main");
        // File copy/cache budget is independent of stdout/stderr collector limits.
        compile.put("copyOutMax",compileArtifactLimitBytes);
        compile.put("copyOutTruncate",false);
        JsonNode compiled=run(compile);
        Verdict cv=compileStatus(compiled);
        if(cv!=Verdict.AC) return new Result(cv);
        String file=compiled.path("fileIds").path("main").asText();
        if(!file.matches("[A-Za-z0-9_-]{1,200}")) throw new IOException("Missing executable cache ID");
        try {
            Result result=new Result(Verdict.AC);
            for(TestCase test:p.getTestCases()) {
                if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                ObjectNode cmd=command(Collections.singletonList("main"),test.getInput(),p.getCpuTimeLimitMs()*1000000L,
                        p.getWallTimeLimitMs()*1000000L,p.getMemoryLimitBytes(),p.getOutputLimitBytes());
                cmd.putObject("copyIn").putObject("main").put("fileId",file);
                JsonNode execution=run(cmd);
                result.timeNs=Math.max(result.timeNs,nonnegativeLong(execution,"time"));
                result.memoryBytes=Math.max(result.memoryBytes,nonnegativeLong(execution,"memory"));
                Verdict verdict=status(execution);
                if(verdict==Verdict.AC && !execution.path("files").path("stdout").isTextual())
                    throw new IOException("Incomplete judge result");
                if(verdict==Verdict.AC && !normalize(execution.path("files").path("stdout").asText()).equals(normalize(test.getExpectedOutput()))) verdict=Verdict.WA;
                if(verdict!=Verdict.AC){result.verdict=verdict;return result;}
            }
            return result;
        } finally { request("DELETE","/file/"+file,null); }
    }
    static String normalize(String value){return value.replace("\r\n","\n").trim();}
    /** No narrowing or string/float coercion is allowed for executor metadata. */
    private static int exitCode(JsonNode result) throws IOException {
        JsonNode value=result.path("exitStatus");
        if(!value.isIntegralNumber() || !value.canConvertToInt()) throw new IOException("Invalid exit status");
        return value.intValue();
    }
    private static long nonnegativeLong(JsonNode result,String field) throws IOException {
        JsonNode value=result.path(field);
        if(!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<0)
            throw new IOException("Invalid executor resource metadata");
        return value.longValue();
    }
    private static boolean executorError(JsonNode result) {
        JsonNode error=result.get("error"),files=result.get("fileError");
        return (error!=null && !error.isNull() && (!error.isTextual() || !error.textValue().isEmpty()))
                || (files!=null && !files.isNull() && (!files.isArray() || files.size()!=0));
    }
    private static boolean nonzeroStatus(String status) {
        return "Nonzero Exit Status".equals(status) || "Non Zero Exit Status".equals(status);
    }
    private static Verdict compileStatus(JsonNode result) throws IOException {
        int code=exitCode(result);
        if(executorError(result)) return Verdict.SYSTEM_ERROR;
        String state=result.path("status").textValue();
        // Only a confirmed ordinary compiler exit is a student compilation error.
        if(nonzeroStatus(state) && code!=0) return Verdict.CE;
        if("Accepted".equals(state) && code==0) return Verdict.AC;
        return Verdict.SYSTEM_ERROR;
    }
    static Verdict status(JsonNode result) throws IOException {
        int code=exitCode(result);
        if(executorError(result)) return Verdict.SYSTEM_ERROR;
        String value=result.path("status").textValue();
        switch(value) {
            case "Accepted":return code==0?Verdict.AC:Verdict.SYSTEM_ERROR;
            case "Time Limit Exceeded":return Verdict.TLE;
            case "Memory Limit Exceeded":return Verdict.MLE;
            case "Output Limit Exceeded":return Verdict.OLE;
            case "Nonzero Exit Status":case "Non Zero Exit Status":return code!=0?Verdict.RE:Verdict.SYSTEM_ERROR;
            case "Signalled":case "Dangerous Syscall":return Verdict.RE;
            default:return Verdict.SYSTEM_ERROR;
        }
    }
    private ObjectNode command(List<String> args,String input,long cpu,long wall,long memory,long output) {
        ObjectNode c=json.createObjectNode();c.set("args",json.valueToTree(args));
        c.putArray("env").add("PATH=/usr/bin:/bin");
        ArrayNode f=c.putArray("files");f.addObject().put("content",input);
        f.addObject().put("name","stdout").put("max",output);f.addObject().put("name","stderr").put("max",output);
        c.put("cpuLimit",cpu).put("clockLimit",wall).put("memoryLimit",memory).put("procLimit",32).put("copyOutMax",output);
        return c;
    }
    private JsonNode run(ObjectNode cmd) throws IOException {
        ObjectNode body=json.createObjectNode();body.putArray("cmd").add(cmd);
        JsonNode response=json.readTree(request("POST","/run",json.writeValueAsBytes(body)));
        if(response==null || !response.isArray() || response.size()!=1 || !response.get(0).isObject()) throw new IOException("Invalid judge response");
        JsonNode result=response.get(0);
        if(!result.path("status").isTextual() || result.path("status").textValue().isEmpty()) throw new IOException("Invalid executor status");
        exitCode(result);
        nonnegativeLong(result,"time");
        nonnegativeLong(result,"memory");
        for(String field:new String[]{"runTime","procPeak"}) if(result.has(field)) nonnegativeLong(result,field);
        return result;
    }
    private byte[] request(String method,String path,byte[] body) throws IOException {
        HttpURLConnection connection=(HttpURLConnection)endpoint.resolve(path).toURL().openConnection();
        long remaining=deadline.get()==null?timeout:TimeUnit.NANOSECONDS.toMillis(deadline.get()-System.nanoTime());
        if(remaining<=0 || Thread.currentThread().isInterrupted()){connection.disconnect();throw new InterruptedIOException("Judge deadline exceeded");}
        int budget=(int)Math.max(1,Math.min(timeout,remaining));
        connection.setConnectTimeout(budget);connection.setReadTimeout(budget);connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        if(!bearer.isEmpty())connection.setRequestProperty("Authorization","Bearer "+bearer);
        try {
            if(body!=null){connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");try(OutputStream out=connection.getOutputStream()){out.write(body);}}
            int code=connection.getResponseCode();if(code<200 || code>=300)throw new IOException("Judge transport failure");
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=connection.getInputStream()) {byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(deadline.get()!=null && System.nanoTime()>=deadline.get())throw new InterruptedIOException("Judge deadline exceeded");if(out.size()+n>2097152)throw new IOException("Judge response too large");out.write(buffer,0,n);}}
            return out.toByteArray();
        } finally {connection.disconnect();}
    }
}
