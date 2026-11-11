package com.example.smartttexam.oj;

import com.example.smartttexam.oj.OjModels.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Operator-managed private catalog. Immutable for the lifetime of a service instance.
 * Never placed in static resources; never returned directly from HTTP.
 */
public final class FileOjProblemCatalog implements OjProblemCatalog {
    private static final int MAX_BYTES=4*1024*1024;
    private static final ObjectMapper JSON=new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public static final class Document {
        public int schemaVersion;
        public List<Entry> problems;
    }
    public static final class Entry {
        public String courseId,classroomId;
        public boolean published;
        public Problem problem;
    }
    private final Document document;
    private FileOjProblemCatalog(Document document){ this.document=document; }
    public static FileOjProblemCatalog load(Path file) throws IOException {
        try {
            if(!file.isAbsolute() || !Files.isRegularFile(file) || Files.isSymbolicLink(file))
                throw new IOException("An absolute private catalog file is required");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream input=Files.newInputStream(file)) {
                byte[] buffer=new byte[8192]; int n;
                while((n=input.read(buffer))!=-1) {
                    if(bytes.size()+n>MAX_BYTES) throw new IOException("Catalog exceeds size limit");
                    bytes.write(buffer,0,n);
                }
            }
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            JsonNode tree=JSON.readTree(text);
            if(tree==null || !tree.isObject()) throw new IOException("Catalog object required");
            validateNumbersAndText(tree);
            if(!tree.path("schemaVersion").isIntegralNumber() || !tree.path("problems").isArray())
                throw new IOException("Invalid catalog types");
            for(JsonNode entry:tree.path("problems")) {
                if(!entry.isObject() || !entry.path("published").isBoolean()
                    || !entry.path("courseId").isTextual() || !entry.path("classroomId").isTextual()
                    || !entry.path("problem").isObject()) throw new IOException("Invalid binding types");
                for(String name:Arrays.asList("cpuTimeLimitMs","wallTimeLimitMs","memoryLimitBytes","outputLimitBytes"))
                    if(!entry.path("problem").path(name).isIntegralNumber()) throw new IOException("Integer limit required");
                for(String name:Arrays.asList("problemId","version","testVersion","statement","language"))
                    if(!entry.path("problem").path(name).isTextual()) throw new IOException("Text field required");
            }
            Document doc=JSON.treeToValue(tree,Document.class);
            validate(doc);
            return new FileOjProblemCatalog(doc);
        } catch(IllegalArgumentException error) { throw new IOException("Invalid catalog",error); }
    }
    private static void validateNumbersAndText(JsonNode node) throws IOException {
        if(node.isNumber() && (!node.isIntegralNumber() || !node.canConvertToLong()))
            throw new IOException("Catalog integer out of range");
        if(node.isTextual()) OjFingerprints.utf8(node.textValue());
        if(node.isContainerNode()) for(JsonNode child:node) validateNumbersAndText(child);
    }
    private static void id(String value) {
        if(value==null || !value.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid identifier");
    }
    private static void range(long value,long min,long max) {
        if(value<min || value>max) throw new IllegalArgumentException("Invalid resource limit");
    }
    private static String key(Entry entry){return entry.courseId+"/"+entry.classroomId+"/"+entry.problem.getProblemId();}
    private static void validate(Document doc) throws IOException {
        if(doc.schemaVersion!=1 || doc.problems==null || doc.problems.size()>200)
            throw new IOException("Invalid catalog schema or count");
        Set<String> bindings=new HashSet<>(); Map<String,String> versions=new HashMap<>(); Map<String,String> tests=new HashMap<>();
        for(Entry entry:doc.problems) {
            if(entry==null || entry.problem==null) throw new IOException("Missing entry");
            id(entry.courseId); id(entry.classroomId);
            Problem p=entry.problem; id(p.getProblemId());id(p.getVersion());id(p.getTestVersion());
            if(!bindings.add(key(entry))) throw new IOException("Duplicate scoped problem");
            if(!"cpp17".equals(p.getLanguage()) || p.getStatement()==null || p.getStatement().trim().isEmpty()
                    || OjFingerprints.utf8(p.getStatement()).length>65536) throw new IOException("Invalid public statement");
            range(p.getCpuTimeLimitMs(),1,10000); range(p.getWallTimeLimitMs(),p.getCpuTimeLimitMs(),30000);
            range(p.getMemoryLimitBytes(),16L*1024*1024,512L*1024*1024);
            range(p.getOutputLimitBytes(),1,1024*1024);
            if(p.getTestCases()==null || p.getTestCases().isEmpty() || p.getTestCases().size()>100)
                throw new IOException("Invalid hidden test count");
            Set<String> cases=new HashSet<>();
            for(TestCase t:p.getTestCases()) {
                if(t==null) throw new IOException("Missing test");
                id(t.getCaseId());
                if(!cases.add(t.getCaseId()) || t.getInput()==null || t.getExpectedOutput()==null
                    || OjFingerprints.utf8(t.getInput()).length>65536
                    || OjFingerprints.utf8(t.getExpectedOutput()).length>p.getOutputLimitBytes())
                    throw new IOException("Invalid hidden test");
            }
            if(p.getKwaIds()==null || p.getKwaIds().size()>100) throw new IOException("Invalid knowledge IDs");
            for(String kwa:p.getKwaIds()) id(kwa);
            String testKey=p.getProblemId()+"/"+p.getTestVersion();
            String content=JSON.writeValueAsString(p.getTestCases()), previousTests=tests.put(testKey,content);
            if(previousTests!=null && !previousTests.equals(content)) throw new IOException("Conflicting immutable test version");
            String versionKey=p.getProblemId()+"/"+p.getVersion();
            String encoded=JSON.writeValueAsString(p),previous=versions.put(versionKey,encoded);
            if(previous!=null && !previous.equals(encoded)) throw new IOException("Conflicting immutable problem version");
        }
    }
    private static Problem copy(Problem p){return JSON.convertValue(p,Problem.class);}
    @Override public Problem get(Owner owner,String id) {
        for(Entry e:document.problems)
            if(e.published && e.courseId.equals(owner.getCourseId()) && e.classroomId.equals(owner.getClassroomId())
                && e.problem.getProblemId().equals(id)) return copy(e.problem);
        throw new IllegalArgumentException("Problem is not published in this scope");
    }
    @Override public List<Problem> list(Owner owner) {
        List<Problem> result=new ArrayList<>();
        for(Entry e:document.problems)
            if(e.published && e.courseId.equals(owner.getCourseId()) && e.classroomId.equals(owner.getClassroomId()))
                result.add(copy(e.problem));
        return result;
    }
    /** Offline administrator action, not an HTTP capability. No implicit parent-directory creation. */
    public static void install(Path source,Path destination) throws IOException {
        FileOjProblemCatalog next=load(source);
        if(!destination.isAbsolute() || !Files.isDirectory(destination.getParent()) || Files.isSymbolicLink(destination))
            throw new IOException("Invalid catalog destination");
        Path lock=destination.resolveSibling(destination.getFileName()+".lock");
        try(FileChannel channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock ignored=channel.tryLock()) {
            if(ignored==null) throw new IOException("Catalog is being updated");
            if(Files.exists(destination)) {
                FileOjProblemCatalog old=load(destination);
                for(Entry previous:old.document.problems) for(Entry candidate:next.document.problems) {
                    if(previous.problem.getProblemId().equals(candidate.problem.getProblemId())
                        && previous.problem.getTestVersion().equals(candidate.problem.getTestVersion())
                        && !JSON.writeValueAsString(previous.problem.getTestCases()).equals(JSON.writeValueAsString(candidate.problem.getTestCases())))
                        throw new IOException("Changed hidden tests require a new test version");
                }
                Map<String,Entry> bindings=new HashMap<>();
                for(Entry e:next.document.problems) bindings.put(key(e),e);
                for(Entry e:old.document.problems) {
                    Entry replacement=bindings.get(key(e));
                    if(replacement==null) throw new IOException("Unpublish existing bindings instead of removing them");
                    if(e.problem.getVersion().equals(replacement.problem.getVersion())
                        && !JSON.writeValueAsString(e.problem).equals(JSON.writeValueAsString(replacement.problem)))
                        throw new IOException("Changed content requires a new problem version");
                }
            }
            Path temp=Files.createTempFile(destination.getParent(),"oj-catalog-",".tmp");
            try {
                byte[] bytes=JSON.writeValueAsBytes(next.document);
                try(FileChannel out=FileChannel.open(temp,StandardOpenOption.WRITE)) {
                    ByteBuffer buffer=ByteBuffer.wrap(bytes); while(buffer.hasRemaining()) out.write(buffer); out.force(true);
                }
                Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        }
    }
    public static void main(String[] args) {
        try {
            if(args.length==2 && "validate".equals(args[0])) load(Paths.get(args[1]));
            else if(args.length==3 && "install".equals(args[0])) install(Paths.get(args[1]),Paths.get(args[2]));
            else throw new IOException("Usage: validate absolute-file | install absolute-source absolute-destination");
            System.out.println("OJ catalog validated; private test data omitted.");
        } catch(Exception error) { System.err.println("OJ catalog operation rejected; no catalog replacement completed.");System.exit(1); }
    }
}
