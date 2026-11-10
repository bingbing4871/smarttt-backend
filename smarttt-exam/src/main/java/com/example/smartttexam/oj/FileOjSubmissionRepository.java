package com.example.smartttexam.oj;

import com.example.smartttexam.oj.OjModels.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/** Single writer, one durable JSON file per submission; no separate idempotency index to tear. */
public final class FileOjSubmissionRepository implements OjSubmissionRepository {
    /** Schema 1 has no metadata checksum; callers must verify/backup old files before opting in. */
    public enum LegacyPolicy { REJECT, MIGRATE_AFTER_EXTERNAL_VERIFICATION }
    private static final long MAX_RECORD_BYTES = 2L * 1024 * 1024;
    private static final ObjectMapper CHECKSUM_MAPPER = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
    private final Path root;
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Clock clock;
    private final PersistenceIO persistenceIO;
    private final FileChannel lockChannel;
    private final FileLock directoryLock;
    private boolean closed;

    public FileOjSubmissionRepository(Path directory) throws IOException { this(directory, Clock.systemUTC()); }

    public FileOjSubmissionRepository(Path directory, Clock clock) throws IOException {
        this(directory, clock, LegacyPolicy.REJECT);
    }

    public FileOjSubmissionRepository(Path directory, Clock clock, LegacyPolicy legacyPolicy) throws IOException {
        this(directory, clock, legacyPolicy, new PersistenceIO());
    }

    FileOjSubmissionRepository(Path directory, Clock clock, LegacyPolicy legacyPolicy, PersistenceIO persistenceIO)
            throws IOException {
        this.clock = Objects.requireNonNull(clock);
        this.persistenceIO = Objects.requireNonNull(persistenceIO);
        Objects.requireNonNull(legacyPolicy);
        root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (!root.equals(root.toRealPath())) throw new IOException("Storage must not traverse a symbolic link");
        Path lockPath = root.resolve(".writer.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("Invalid storage lock path");
        lockChannel = FileChannel.open(lockPath, CREATE, WRITE);
        FileLock acquired;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null) throw new IOException("OJ storage is already open by another writer");
        } catch (IOException | OverlappingFileLockException ex) {
            lockChannel.close(); throw new IOException("Cannot acquire exclusive OJ storage lock", ex);
        }
        directoryLock = acquired;
        try {
            // Fail closed on corrupt records. Incomplete temporary files are not acknowledged submissions.
            List<Submission> records = all(true);
            boolean legacyPresent = records.stream().anyMatch(record -> record.getSchemaVersion() == 1);
            if (legacyPresent && legacyPolicy == LegacyPolicy.REJECT)
                throw new IOException("Schema 1 records lack metadata checksums. Preserve and externally verify/backup "
                        + "history, then explicitly use MIGRATE_AFTER_EXTERNAL_VERIFICATION; no files were migrated.");
            // Validate the entire directory before any migration/recovery writes. Mixed versions can resume migration.
            for (Submission record : records) {
                boolean rewrite = record.getSchemaVersion() == 1;
                if (record.getStatus() == TaskStatus.RUNNING) {
                    record.setStatus(TaskStatus.INTERRUPTED); rewrite = true;
                }
                if (rewrite) write(record);
            }
        } catch (IOException | RuntimeException ex) {
            try { close(); } catch (IOException closeError) { ex.addSuppressed(closeError); }
            throw ex;
        }
    }

    @Override public synchronized Submission create(Owner owner, String requestId, Problem problem,
                                                    String source, String language) throws IOException {
        ensureOpen();
        OjFingerprints.utf8(source); // Also guard direct repository callers before IDs/fingerprints or disk writes.
        String id = OjFingerprints.id(owner, requestId);
        String fingerprint = OjFingerprints.content(owner, problem, source, language);
        Path destination = path(id);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            Submission existing = read(destination);
            if (!existing.getFingerprint().equals(fingerprint))
                throw new IllegalArgumentException("Request ID already used with different submission content");
            return existing;
        }
        long highestAttempt = 0;
        for (Submission previous : all()) {
            if (previous.getOwner().equals(owner) && previous.getProblem().getProblemId().equals(problem.getProblemId()))
                highestAttempt = Math.max(highestAttempt, previous.getAttemptNumber());
        }
        Submission submission = new Submission();
        submission.setSubmissionId(id); submission.setRequestId(requestId); submission.setFingerprint(fingerprint);
        submission.setOwner(owner); submission.setProblem(problem); submission.setSourceCode(source);
        submission.setSourceSha256(OjFingerprints.sha256(source)); submission.setLanguage(language);
        submission.setAttemptNumber(checkedNextAttempt(highestAttempt)); submission.setSubmittedAt(clock.instant().toString());
        submission.setStatus(TaskStatus.WAITING_JUDGE);
        write(submission);
        return read(destination);
    }

    @Override public synchronized Submission find(Owner owner, String submissionId) throws IOException {
        ensureOpen(); Path file = path(submissionId);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        Submission submission = read(file);
        return submission.getOwner().equals(owner) ? submission : null;
    }

    @Override public synchronized List<Submission> history(Owner owner, String problemId, int offset, int limit) throws IOException {
        ensureOpen();
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid history pagination");
        List<Submission> matches = new ArrayList<>();
        for (Submission record : all()) {
            if (record.getOwner().equals(owner) && (problemId == null || record.getProblem().getProblemId().equals(problemId)))
                matches.add(record);
        }
        matches.sort(Comparator.comparing((Submission record) -> Instant.parse(record.getSubmittedAt())).reversed()
                .thenComparing(Comparator.comparingLong(Submission::getAttemptNumber).reversed())
                .thenComparing(Submission::getSubmissionId));
        int from = Math.min(offset, matches.size());
        return new ArrayList<>(matches.subList(from, (int) Math.min((long) from + limit, matches.size())));
    }

    /** Validated detached snapshots for the single-process judge dispatcher. */
    public synchronized List<Submission> snapshots() throws IOException {
        ensureOpen(); return all();
    }

    private List<Submission> all() throws IOException {
        return all(false);
    }

    private List<Submission> all(boolean allowLegacy) throws IOException {
        List<Submission> records = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "*.json")) {
            for (Path file : files) records.add(read(file, allowLegacy));
        }
        Map<String, SortedSet<Long>> sequences = new HashMap<>();
        for (Submission record : records) {
            String group = OjFingerprints.id(record.getOwner(), record.getProblem().getProblemId());
            if (!sequences.computeIfAbsent(group, key -> new TreeSet<>()).add(record.getAttemptNumber()))
                throw new IOException("Duplicate submission attempt number in history");
        }
        for (SortedSet<Long> sequence : sequences.values()) {
            long previous = 0;
            for (long attempt : sequence) {
                if (attempt != checkedNextAttempt(previous))
                    throw new IOException("Submission attempt numbers must be contiguous starting at 1");
                previous = attempt;
            }
        }
        return records;
    }

    static long checkedNextAttempt(long previous) throws IOException {
        if (previous < 0) throw new IOException("Invalid previous submission attempt number");
        try { return Math.addExact(previous, 1L); }
        catch (ArithmeticException ex) { throw new IOException("Submission attempt number overflow", ex); }
    }

    private Path path(String id) {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid submission ID");
        return root.resolve(id + ".json");
    }

    private Submission read(Path file) throws IOException {
        return read(file, false);
    }

    private Submission read(Path file, boolean allowLegacy) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_RECORD_BYTES)
            throw new IOException("Invalid submission file: " + file.getFileName());
        JsonNode json = mapper.readTree(Files.readAllBytes(file));
        if (json == null || !json.isObject()) throw new IOException("Invalid submission object");
        for (String field : new String[]{"schemaVersion", "submissionId", "requestId", "fingerprint", "owner", "problem",
                "sourceCode", "sourceSha256", "language", "attemptNumber", "submittedAt", "status", "executionMode", "judgeAttempts"}) {
            if (!json.hasNonNull(field)) throw new IOException("Missing submission field: " + field);
        }
        if (!json.get("schemaVersion").isIntegralNumber() || !json.get("schemaVersion").canConvertToInt()
                || !json.get("attemptNumber").isIntegralNumber() || !json.get("attemptNumber").canConvertToLong())
            throw new IOException("Invalid submission schema or attempt number type/range");
        Submission result = mapper.treeToValue(json, Submission.class);
        try {
            boolean valid = (result.getSchemaVersion() == 2 || (result.getSchemaVersion() == 1 && allowLegacy
                    && result.getRecordChecksum() == null)) && result.getAttemptNumber() > 0
                    && result.getStatus() != null && result.getExecutionMode() != null
                    && result.getJudgeAttempts() != null
                    && result.getSubmissionId().equals(OjFingerprints.id(result.getOwner(), result.getRequestId()))
                    && file.equals(path(result.getSubmissionId()))
                    && result.getSourceSha256().equals(OjFingerprints.sha256(result.getSourceCode()))
                    && result.getFingerprint().equals(OjFingerprints.content(result.getOwner(), result.getProblem(),
                        result.getSourceCode(), result.getLanguage()));
            Instant.parse(result.getSubmittedAt());
            if (result.getSchemaVersion() == 2)
                valid &= recordChecksum(result).equals(result.getRecordChecksum());
            if (!valid) throw new IllegalArgumentException("Invalid record");
        } catch (RuntimeException ex) { throw new IOException("Submission integrity check failed: " + file.getFileName(), ex); }
        return result;
    }

    private void write(Submission record) throws IOException {
        record.setSchemaVersion(2);
        record.setRecordChecksum(recordChecksum(record));
        byte[] data = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(record);
        if (data.length > MAX_RECORD_BYTES) throw new IOException("Submission record exceeds size limit");
        Path temporary = Files.createTempFile(root, ".submission-", ".tmp");
        try {
            persistenceIO.writeAndForce(temporary, data);
            // No non-atomic fallback: an unsupported filesystem must fail rather than acknowledge a partial write.
            persistenceIO.atomicMove(temporary, path(record.getSubmissionId()));
        } finally { Files.deleteIfExists(temporary); }
    }

    /** Version-2 checksum covers all persisted model fields except itself, including mutable metadata. */
    static String recordChecksum(Submission record) throws IOException {
        ObjectNode json = CHECKSUM_MAPPER.valueToTree(record);
        json.remove("recordChecksum");
        return OjFingerprints.sha256(CHECKSUM_MAPPER.writeValueAsString(json));
    }

    /** Package-private seam for deterministic write/move fault tests; production uses these real NIO operations. */
    static class PersistenceIO {
        void writeAndForce(Path temporary, byte[] data) throws IOException {
            try (FileChannel channel = FileChannel.open(temporary, WRITE, TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
        }
        void atomicMove(Path temporary, Path destination) throws IOException {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void ensureOpen() throws IOException { if (closed) throw new IOException("OJ storage is closed"); }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { directoryLock.release(); } finally { lockChannel.close(); }
    }
}
