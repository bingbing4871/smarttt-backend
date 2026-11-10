package com.example.smartttexam.oj.judge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonParser;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/** Separate result journal: accepted submission files are never rewritten by judging. */
public final class OjJudgeJournal implements AutoCloseable {
    public static class Entry {
        public String submissionId,state,verdict,checksum;
        public int attempts;
        public long timeNs,memoryBytes;
        public Entry() { }
    }
    private final Path root;
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
    private final FileChannel channel; private final FileLock lock; private boolean closed;
    public OjJudgeJournal(Path root) throws IOException {
        this.root=root.toAbsolutePath().normalize();Files.createDirectories(this.root);
        if(!this.root.equals(this.root.toRealPath()) || Files.isSymbolicLink(this.root.resolve(".judge.lock")))throw new IOException("Invalid judge directory");
        channel=FileChannel.open(this.root.resolve(".judge.lock"),CREATE,WRITE);
        FileLock acquired;
        try {acquired=channel.tryLock();if(acquired==null)throw new IOException("Judge already open");}
        catch(IOException|RuntimeException e){channel.close();throw e;}
        lock=acquired;
        try(DirectoryStream<Path> files=Files.newDirectoryStream(this.root,"*.json")) {
            for(Path file:files){String name=file.getFileName().toString();read(name.substring(0,name.length()-5));}
        }catch(IOException|RuntimeException e){close();throw e;}
    }
    private Path file(String id){if(id==null || !id.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid judge ID");return root.resolve(id+".json");}
    public synchronized Entry read(String id) throws IOException {
        if(closed)throw new IOException("Closed journal");Path f=file(id);
        if(!Files.exists(f,LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS)||Files.size(f)>16384)throw new IOException("Invalid journal file");
        JsonNode tree=json.readTree(Files.readAllBytes(f));
        if(tree==null || !tree.isObject())throw new IOException("Invalid journal object");
        for(String field:new String[]{"submissionId","state","checksum","attempts","timeNs","memoryBytes"})
            if(!tree.hasNonNull(field))throw new IOException("Missing journal field");
        if(!tree.path("attempts").isIntegralNumber() || !tree.path("attempts").canConvertToInt()
                || !tree.path("timeNs").isIntegralNumber() || !tree.path("timeNs").canConvertToLong()
                || !tree.path("memoryBytes").isIntegralNumber() || !tree.path("memoryBytes").canConvertToLong())throw new IOException("Invalid journal numbers");
        Entry entry=json.treeToValue(tree,Entry.class);
        if(entry.timeNs<0 || entry.memoryBytes<0 || !id.equals(entry.submissionId)||entry.attempts<1||entry.attempts>2
                ||!Arrays.asList("RUNNING","RETRY","DONE","FAILED").contains(entry.state)
                ||!digest(entry).equals(entry.checksum))throw new IOException("Journal integrity failure");
        if(entry.state.equals("DONE"))try{com.example.smartttexam.oj.OjModels.Verdict.valueOf(entry.verdict);}catch(RuntimeException e){throw new IOException("Invalid verdict",e);}
        return entry;
    }
    public synchronized void write(Entry entry) throws IOException {
        if(closed)throw new IOException("Closed journal");Path destination=file(entry.submissionId);
        entry.checksum=digest(entry);byte[] bytes=json.writeValueAsBytes(entry);
        Path temporary=Files.createTempFile(root,".result-",".tmp");
        try {
            try(FileChannel out=FileChannel.open(temporary,WRITE)){ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())out.write(b);out.force(true);}
            Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary);}
    }
    private String digest(Entry entry) throws IOException {
        JsonNode tree=json.valueToTree(entry);((com.fasterxml.jackson.databind.node.ObjectNode)tree).remove("checksum");
        try{byte[] b=MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(tree));StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format("%02x",v&255));return s.toString();}
        catch(NoSuchAlgorithmException e){throw new IOException(e);}
    }
    @Override public synchronized void close() throws IOException {if(!closed){closed=true;try{lock.release();}finally{channel.close();}}}
}
