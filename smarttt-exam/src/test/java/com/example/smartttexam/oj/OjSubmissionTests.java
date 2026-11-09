package com.example.smartttexam.oj;

import com.example.smartttexam.oj.OjModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated JUnit Jupiter tests; no Spring context or external business resources. */
public class OjSubmissionTests {
    private static final Owner OWNER = new Owner("fixture-student", "fixture-course", "fixture-classroom");
    private static final String SOURCE = "// 测试代码\r\nint main() { return 0; }\n";
    @TempDir Path base;
    private Path directory() throws IOException {
        Path artifacts = base;
        Files.createDirectories(artifacts);
        return Files.createTempDirectory(artifacts, "case-");
    }
    private OjSubmissionService service(OjSubmissionRepository repository) {
        return new OjSubmissionService(repository, new FixedOjProblemCatalog());
    }
    private Submission submit(OjSubmissionService service, String requestId) throws IOException {
        return service.submit(OWNER, requestId, FixedOjProblemCatalog.PROBLEM_ID, "cpp17", SOURCE);
    }

    @Test void savesExactSourceAndProblemSnapshot() throws Exception {
        Path root = directory();
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
            Submission record = submit(service(repository), "save");
            assertEquals(SOURCE, record.getSourceCode());
            assertEquals(OjFingerprints.sha256(SOURCE), record.getSourceSha256());
            assertEquals(TaskStatus.WAITING_JUDGE, record.getStatus());
            assertEquals(ExecutionMode.NOT_RUN, record.getExecutionMode());
            assertTrue(record.getJudgeAttempts().isEmpty());
            assertEquals(4, record.getProblem().getTestCases().size());
            assertEquals("-5\n", record.getProblem().getTestCases().get(2).getExpectedOutput());
            assertEquals(1, record.getAttemptNumber());
            assertTrue(Files.isRegularFile(root.resolve(record.getSubmissionId() + ".json")));
            Instant.parse(record.getSubmittedAt());
        }
    }

    @Test void duplicateRequestReturnsOriginalAndChangedContentConflicts() throws Exception {
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(directory())) {
            OjSubmissionService service = service(repository);
            Submission first = submit(service, "same");
            assertEquals(first, submit(service, "same"));
            assertThrows(IllegalArgumentException.class, () -> service.submit(OWNER, "same",
                    FixedOjProblemCatalog.PROBLEM_ID, "cpp17", "int main(){}"));
            assertEquals(1, service.history(OWNER, null, 0, 100).size());
        }
    }

    @Test void deliberateResubmissionPreservesAttempts() throws Exception {
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(directory())) {
            OjSubmissionService service = service(repository);
            Submission first = submit(service, "first"), second = submit(service, "second");
            assertNotEquals(first.getSubmissionId(), second.getSubmissionId());
            assertEquals(2, second.getAttemptNumber());
            assertEquals(first, service.find(OWNER, first.getSubmissionId()));
        }
    }

    @Test void restartKeepsHistoryAndIdempotency() throws Exception {
        Path root = directory(); Submission saved;
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
            saved = submit(service(repository), "restart");
        }
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
            OjSubmissionService service = service(repository);
            assertEquals(saved, service.find(OWNER, saved.getSubmissionId()));
            assertEquals(saved, submit(service, "restart"));
            assertEquals(2, submit(service, "next").getAttemptNumber());
        }
    }

    @Test void concurrentDuplicateAndUniqueRequestsRemainConsistent() throws Exception {
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(directory())) {
            OjSubmissionService service = service(repository);
            ExecutorService pool = newPool(8);
            try {
                List<Callable<Submission>> requests = new ArrayList<>();
                for (int i = 0; i < 32; i++) requests.add(() -> submit(service, "concurrent"));
                Set<String> ids = new HashSet<>();
                for (Submission result : concurrentResults(pool, requests, 10000)) ids.add(result.getSubmissionId());
                assertEquals(1, ids.size());
                requests.clear();
                for (int i = 0; i < 16; i++) { final String id = "unique-" + i; requests.add(() -> submit(service, id)); }
                Set<Long> attempts = new HashSet<>();
                for (Submission result : concurrentResults(pool, requests, 10000)) attempts.add(result.getAttemptNumber());
                assertEquals(16, attempts.size());
                assertEquals(17, service.history(OWNER, null, 0, 100).size());
            } finally { stopPool(pool); }
        }
    }

    @Test void readersAreScopedToStudentCourseAndClassroom() throws Exception {
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(directory())) {
            OjSubmissionService service = service(repository); Submission saved = submit(service, "private");
            for (Owner other : Arrays.asList(new Owner("other", "fixture-course", "fixture-classroom"),
                    new Owner("fixture-student", "other-course", "fixture-classroom"),
                    new Owner("fixture-student", "fixture-course", "other-classroom"))) {
                assertNull(service.find(other, saved.getSubmissionId()));
                assertTrue(service.history(other, null, 0, 100).isEmpty());
            }
        }
    }

    @Test void validatesInputAndPreventsPathTraversal() throws Exception {
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(directory())) {
            OjSubmissionService service = service(repository);
            assertThrows(IllegalArgumentException.class, () -> submit(service, "../escape"));
            assertThrows(IllegalArgumentException.class, () -> service.find(OWNER, "../escape"));
            assertThrows(IllegalArgumentException.class, () -> service.submit(null, "id", FixedOjProblemCatalog.PROBLEM_ID, "cpp17", SOURCE));
            assertThrows(IllegalArgumentException.class, () -> service.submit(OWNER, "id", "unknown", "cpp17", SOURCE));
            assertThrows(IllegalArgumentException.class, () -> service.submit(OWNER, "id", FixedOjProblemCatalog.PROBLEM_ID, "python", SOURCE));
            assertThrows(IllegalArgumentException.class, () -> service.submit(OWNER, "id", FixedOjProblemCatalog.PROBLEM_ID, "cpp17", " "));
            String oversized = String.join("", Collections.nCopies(100000, "中"));
            assertThrows(IllegalArgumentException.class, () -> service.submit(OWNER, "id", FixedOjProblemCatalog.PROBLEM_ID, "cpp17", oversized));
            assertThrows(IllegalArgumentException.class, () -> service.history(OWNER, null, -1, 10));
            assertThrows(IllegalArgumentException.class, () -> service.history(OWNER, null, 0, 101));
            assertTrue(service.history(OWNER, null, 0, 100).isEmpty());
        }
    }

    @Test void rejectsConcurrentWritersAndReleasesLock() throws Exception {
        Path root = directory();
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
            assertThrows(IOException.class, () -> new FileOjSubmissionRepository(root));
        }
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
            assertEquals(1, submit(service(repository), "reopened").getAttemptNumber());
        }
    }

    @Test void corruptRecordsBlockStartupRatherThanEraseHistory() throws Exception {
        Path root = directory(); Submission saved;
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) { saved = submit(service(repository), "corrupt"); }
        Path file = root.resolve(saved.getSubmissionId() + ".json");
        byte[] original = Files.readAllBytes(file);
        Files.write(file, "broken".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> new FileOjSubmissionRepository(root));
        assertEquals("broken", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        Files.write(file, original);
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
            assertEquals(saved, service(repository).find(OWNER, saved.getSubmissionId()));
        }
    }

    @Test void detectsChangedSourceAndRetainsPartialTemporaryFile() throws Exception {
        Path root = directory(); Submission saved;
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) { saved = submit(service(repository), "hash"); }
        Path file = root.resolve(saved.getSubmissionId() + ".json");
        byte[] original = Files.readAllBytes(file);
        saved.setSourceCode("changed source");
        new ObjectMapper().writeValue(file.toFile(), saved);
        assertThrows(IOException.class, () -> new FileOjSubmissionRepository(root));
        Files.write(file, original);
        Files.write(root.resolve(".submission-partial.tmp"), "partial".getBytes(StandardCharsets.UTF_8));
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
            assertEquals(1, service(repository).history(OWNER, null, 0, 100).size());
        }
        assertTrue(Files.exists(root.resolve(".submission-partial.tmp")));
    }

    @Test void startupMarksInFlightExecutionInterrupted() throws Exception {
        Path root = directory(); Submission saved;
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) { saved = submit(service(repository), "in-flight"); }
        saved.setStatus(TaskStatus.RUNNING);
        saved.setRecordChecksum(FileOjSubmissionRepository.recordChecksum(saved));
        new ObjectMapper().writeValue(root.resolve(saved.getSubmissionId() + ".json").toFile(), saved);
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
            assertEquals(TaskStatus.INTERRUPTED, service(repository).find(OWNER, saved.getSubmissionId()).getStatus());
            assertEquals(SOURCE, service(repository).find(OWNER, saved.getSubmissionId()).getSourceCode());
        }
    }

    @Test void returnedObjectsCannotChangeStoredRecord() throws Exception {
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(directory())) {
            OjSubmissionService service = service(repository); Submission saved = submit(service, "copy");
            String id = saved.getSubmissionId(); saved.getOwner().setStudentId("attacker");
            saved.getProblem().getTestCases().clear(); saved.setSourceCode("modified");
            Submission actual = service.find(OWNER, id);
            assertEquals(SOURCE, actual.getSourceCode()); assertEquals(4, actual.getProblem().getTestCases().size());
        }
    }

    @Test void failedPersistenceNeverReturnsAnAcknowledgement() throws Exception {
        Path root = directory();
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
            OjSubmissionService service = service(repository);
            // Existing non-file at the target path simulates an unusable destination without changing permissions.
            Files.createDirectory(root.resolve(OjFingerprints.id(OWNER, "blocked") + ".json"));
            assertThrows(IOException.class, () -> submit(service, "blocked"));
        }
    }

    @Test void closedStorageRejectsFurtherOperations() throws Exception {
        FileOjSubmissionRepository repository = new FileOjSubmissionRepository(directory());
        OjSubmissionService service = service(repository); repository.close();
        assertThrows(IOException.class, () -> submit(service, "closed"));
    }

    @Test void historyPaginationIsStableAtIdenticalTimestamps() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"), ZoneOffset.UTC);
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(directory(), clock)) {
            OjSubmissionService service = service(repository);
            submit(service, "one"); submit(service, "two"); submit(service, "three");
            assertEquals(3, service.history(OWNER, null, 0, 1).get(0).getAttemptNumber());
            assertEquals(2, service.history(OWNER, null, 1, 1).get(0).getAttemptNumber());
            assertTrue(service.history(OWNER, null, Integer.MAX_VALUE, 100).isEmpty());
        }
    }

    @Test void historyOrdersInstantsWithDifferentFractionalPrecision() throws Exception {
        Path root = directory();
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root,
                Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"), ZoneOffset.UTC))) {
            submit(service(repository), "whole-second");
        }
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root,
                Clock.fixed(Instant.parse("2026-11-09T00:00:00.001Z"), ZoneOffset.UTC))) {
            Submission latest = submit(service(repository), "fractional");
            assertEquals(latest.getSubmissionId(), service(repository).history(OWNER, null, 0, 1).get(0).getSubmissionId());
        }
    }

    @Test void rejectsMalformedUnicodeBeforePersistence() throws Exception {
        Path root = directory();
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
            OjSubmissionService service = service(repository);
            for (String invalid : Arrays.asList("\uD800", "\uDC00", "\uD800x", "\uDC00\uD800")) {
                assertThrows(IllegalArgumentException.class, () -> service.submit(OWNER, "unicode",
                        FixedOjProblemCatalog.PROBLEM_ID, "cpp17", SOURCE + invalid));
                assertThrows(IllegalArgumentException.class, () -> OjFingerprints.sha256(invalid));
                assertFalse(Files.exists(root.resolve(OjFingerprints.id(OWNER, "unicode") + ".json")));
            }
            assertTrue(service.history(OWNER, null, 0, 100).isEmpty());
            Submission valid = service.submit(OWNER, "unicode", FixedOjProblemCatalog.PROBLEM_ID, "cpp17", SOURCE + "?");
            assertEquals(SOURCE + "?", valid.getSourceCode());
            assertEquals(1, valid.getAttemptNumber());
        }
    }

    @Test void repositoryRejectsMalformedUnicodeBeforeHashing() throws Exception {
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(directory())) {
            assertThrows(IllegalArgumentException.class, () -> repository.create(OWNER, "direct",
                    new FixedOjProblemCatalog().get(FixedOjProblemCatalog.PROBLEM_ID), SOURCE + "\uD800", "cpp17"));
            assertTrue(repository.history(OWNER, null, 0, 100).isEmpty());
        }
    }

    @Test void preservesValidUnicodeWithoutNormalizationAndRejectsConflicts() throws Exception {
        Path root = directory(); Submission first;
        String composed = SOURCE + "// 中文 \uD83D\uDE00 é\r\n";
        String decomposed = SOURCE + "// 中文 \uD83D\uDE00 e\u0301\r\n";
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
            OjSubmissionService service = service(repository);
            first = service.submit(OWNER, "valid-unicode", FixedOjProblemCatalog.PROBLEM_ID, "cpp17", composed);
            assertEquals(composed, first.getSourceCode());
            assertEquals(first, service.submit(OWNER, "valid-unicode", FixedOjProblemCatalog.PROBLEM_ID, "cpp17", composed));
            assertThrows(IllegalArgumentException.class, () -> service.submit(OWNER, "valid-unicode",
                    FixedOjProblemCatalog.PROBLEM_ID, "cpp17", decomposed));
            Submission second = service.submit(OWNER, "other-unicode", FixedOjProblemCatalog.PROBLEM_ID, "cpp17", decomposed);
            assertEquals(decomposed, second.getSourceCode());
            assertNotEquals(first.getSourceSha256(), second.getSourceSha256());
            assertNotEquals(first.getFingerprint(), second.getFingerprint());
        }
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
            assertEquals(first, service(repository).find(OWNER, first.getSubmissionId()));
        }
    }

    @Test void checksumDetectsCriticalMetadataChanges() throws Exception {
        for (int field = 0; field < 5; field++) {
            Path root = directory(); Submission saved;
            try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) { saved = submit(service(repository), "metadata"); }
            switch (field) {
                case 0: saved.setAttemptNumber(2); break;
                case 1: saved.setSubmittedAt("2020-01-01T00:00:00Z"); break;
                case 2: saved.setStatus(TaskStatus.RUNNING); break;
                case 3: saved.setExecutionMode(ExecutionMode.MOCK); break;
                default: JudgeAttempt attempt = new JudgeAttempt(); attempt.setAttemptId("unexpected"); saved.getJudgeAttempts().add(attempt);
            }
            rewrite(root, saved, false);
            byte[] damaged = Files.readAllBytes(recordPath(root, saved));
            assertThrows(IOException.class, () -> new FileOjSubmissionRepository(root));
            assertArrayEquals(damaged, Files.readAllBytes(recordPath(root, saved)));
        }
    }

    @Test void rejectsMissingAndNonintegralMetadata() throws Exception {
        for (String damage : Arrays.asList("missing-attempt", "fractional-attempt", "out-of-range", "missing-schema", "missing-mode", "missing-checksum")) {
            Path root = directory(); Submission saved;
            try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) { saved = submit(service(repository), "shape"); }
            ObjectMapper mapper = new ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode json = mapper.valueToTree(saved);
            switch (damage) {
                case "missing-attempt": json.remove("attemptNumber"); break;
                case "fractional-attempt": json.put("attemptNumber", 1.5); break;
                case "out-of-range": json.put("attemptNumber", new java.math.BigInteger("9223372036854775808")); break;
                case "missing-schema": json.remove("schemaVersion"); break;
                case "missing-mode": json.remove("executionMode"); break;
                default: json.remove("recordChecksum");
            }
            mapper.writeValue(recordPath(root, saved).toFile(), json);
            assertThrows(IOException.class, () -> new FileOjSubmissionRepository(root));
        }
    }

    @Test void rejectsSchemaDowngradeWhileStorageIsOpen() throws Exception {
        Path root = directory();
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
            OjSubmissionService service = service(repository); Submission saved = submit(service, "downgrade");
            saved.setSchemaVersion(1); rewrite(root, saved, false);
            assertThrows(IOException.class, () -> service.find(OWNER, saved.getSubmissionId()));
            writeLegacy(root, saved); // Removing the checksum must not bypass checks on an already-open repository.
            assertThrows(IOException.class, () -> service.find(OWNER, saved.getSubmissionId()));
            assertThrows(IOException.class, () -> service.history(OWNER, null, 0, 100));
            assertThrows(IOException.class, () -> submit(service, "downgrade"));
            assertThrows(IOException.class, () -> submit(service, "new-request"));
        }
    }

    @Test void rejectsAbnormalAttemptNumbersEvenWithMatchingChecksum() throws Exception {
        for (long number : new long[]{0, -1, Long.MAX_VALUE}) {
            Path root = directory(); Submission saved;
            try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) { saved = submit(service(repository), "number"); }
            saved.setAttemptNumber(number); rewrite(root, saved, true);
            assertThrows(IOException.class, () -> new FileOjSubmissionRepository(root));
        }
    }

    @Test void rejectsDuplicateAndGappedAttemptSequences() throws Exception {
        for (long number : new long[]{1, 3}) {
            Path root = directory(); Submission second;
            try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
                submit(service(repository), "first"); second = submit(service(repository), "second");
            }
            second.setAttemptNumber(number); rewrite(root, second, true);
            IOException failure = assertThrows(IOException.class, () -> new FileOjSubmissionRepository(root));
            assertTrue(failure.getMessage().contains(number == 1 ? "Duplicate" : "contiguous"));
        }
    }

    @Test void safeAttemptIncrementFailsInsteadOfWrapping() throws Exception {
        assertEquals(1, FileOjSubmissionRepository.checkedNextAttempt(0));
        assertEquals(Long.MAX_VALUE, FileOjSubmissionRepository.checkedNextAttempt(Long.MAX_VALUE - 1));
        IOException failure = assertThrows(IOException.class, () -> FileOjSubmissionRepository.checkedNextAttempt(Long.MAX_VALUE));
        assertTrue(failure.getMessage().contains("overflow"));
        assertThrows(IOException.class, () -> FileOjSubmissionRepository.checkedNextAttempt(-1));
    }

    @Test void sequenceValidationKeepsDifferentOwnersIndependent() throws Exception {
        Path root = directory(); Owner other = new Owner("other-student", "fixture-course", "fixture-classroom");
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
            OjSubmissionService service = service(repository);
            assertEquals(1, submit(service, "scope-first").getAttemptNumber());
            assertEquals(1, service.submit(other, "scope-first", FixedOjProblemCatalog.PROBLEM_ID, "cpp17", SOURCE).getAttemptNumber());
            assertEquals(2, submit(service, "scope-second").getAttemptNumber());
        }
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
            assertEquals(2, service(repository).history(OWNER, null, 0, 100).size());
            assertEquals(1, service(repository).history(other, null, 0, 100).size());
        }
    }

    @Test void legacyRecordsRequireExplicitVerifiedMigration() throws Exception {
        Path root = directory(); Submission first, second;
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
            first = submit(service(repository), "legacy-one"); second = submit(service(repository), "legacy-two");
        }
        writeLegacy(root, first); writeLegacy(root, second);
        byte[] original = Files.readAllBytes(recordPath(root, first));
        IOException failure = assertThrows(IOException.class, () -> new FileOjSubmissionRepository(root));
        assertTrue(failure.getMessage().contains("MIGRATE_AFTER_EXTERNAL_VERIFICATION"));
        assertArrayEquals(original, Files.readAllBytes(recordPath(root, first)));
        try (FileOjSubmissionRepository repository = migratingRepository(root)) {
            Submission migrated = service(repository).find(OWNER, first.getSubmissionId());
            assertEquals(2, migrated.getSchemaVersion());
            assertNotNull(migrated.getRecordChecksum());
            assertEquals(first.getSourceCode(), migrated.getSourceCode());
            assertEquals(first.getSubmittedAt(), migrated.getSubmittedAt());
            assertEquals(first.getFingerprint(), migrated.getFingerprint());
            assertEquals(migrated, submit(service(repository), "legacy-one"));
            assertEquals(3, submit(service(repository), "after-migration").getAttemptNumber());
        }
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
            assertEquals(3, service(repository).history(OWNER, null, 0, 100).size());
            assertEquals(4, submit(service(repository), "after-restart").getAttemptNumber());
        }
    }

    @Test void legacyMigrationValidatesWholeHistoryBeforeWriting() throws Exception {
        Path root = directory(); Submission first, second;
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
            first = submit(service(repository), "old-one"); second = submit(service(repository), "old-two");
        }
        writeLegacy(root, first); second.setAttemptNumber(1); writeLegacy(root, second);
        byte[] original = Files.readAllBytes(recordPath(root, first));
        assertThrows(IOException.class, () -> migratingRepository(root));
        assertArrayEquals(original, Files.readAllBytes(recordPath(root, first)));
        assertEquals(1, new ObjectMapper().readTree(recordPath(root, second).toFile()).get("schemaVersion").intValue());
    }

    @Test void interruptedLegacyMigrationCanResumeWithoutLosingHistory() throws Exception {
        Path root = directory(); Submission first, second;
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
            first = submit(service(repository), "mixed-one"); second = submit(service(repository), "mixed-two");
        }
        writeLegacy(root, first); writeLegacy(root, second);
        FileOjSubmissionRepository.PersistenceIO io = new FileOjSubmissionRepository.PersistenceIO() {
            private int moves;
            @Override void atomicMove(Path temporary, Path destination) throws IOException {
                if (++moves == 2) throw new IOException("Injected migration move failure");
                super.atomicMove(temporary, destination);
            }
        };
        assertThrows(IOException.class, () -> new FileOjSubmissionRepository(root, Clock.systemUTC(),
                FileOjSubmissionRepository.LegacyPolicy.MIGRATE_AFTER_EXTERNAL_VERIFICATION, io));
        Set<Integer> versions = new HashSet<>();
        for (Submission record : Arrays.asList(first, second))
            versions.add(new ObjectMapper().readTree(recordPath(root, record).toFile()).get("schemaVersion").intValue());
        assertEquals(new HashSet<>(Arrays.asList(1, 2)), versions);
        try (FileOjSubmissionRepository repository = migratingRepository(root)) {
            assertEquals(2, service(repository).history(OWNER, null, 0, 100).size());
            assertEquals(3, submit(service(repository), "resumed").getAttemptNumber());
        }
    }

    @Test void temporaryWriteFailureIsNotAcknowledgedAndCanRetry() throws Exception { verifyWriteFailure(true); }

    @Test void atomicMoveFailureIsNotAcknowledgedAndCanRetry() throws Exception { verifyWriteFailure(false); }

    private void verifyWriteFailure(boolean duringWrite) throws Exception {
        Path root = directory(); FailingIO io = new FailingIO(); Submission saved, retry;
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root, Clock.systemUTC(),
                FileOjSubmissionRepository.LegacyPolicy.REJECT, io)) {
            OjSubmissionService service = service(repository); saved = submit(service, "durable");
            io.failWrite = duringWrite; io.failMove = !duringWrite;
            IOException failure = assertThrows(IOException.class, () -> submit(service, "retry"));
            if (duringWrite) assertEquals("Injected partial write failure", failure.getMessage());
            else {
                assertTrue(failure instanceof AtomicMoveNotSupportedException);
                assertTrue(failure.getMessage().endsWith("Injected atomic move failure"));
            }
            assertTrue(io.lastWrittenBytes > 0); // The fault is inside the persistence path, after a real temporary write.
            assertFalse(Files.exists(io.lastTemporary));
            assertFalse(Files.exists(root.resolve(OjFingerprints.id(OWNER, "retry") + ".json")));
            assertNull(service.find(OWNER, OjFingerprints.id(OWNER, "retry")));
            assertEquals(Collections.singletonList(saved), service.history(OWNER, null, 0, 100));
            retry = submit(service, "retry");
            assertEquals(2, retry.getAttemptNumber());
            assertEquals(retry, submit(service, "retry"));
        }
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
            OjSubmissionService service = service(repository);
            assertEquals(saved, service.find(OWNER, saved.getSubmissionId()));
            assertEquals(retry, service.find(OWNER, retry.getSubmissionId()));
            assertEquals(2, service.history(OWNER, null, 0, 100).size());
        }
    }

    @Test void failedWritePreservesHistoryAcrossReopenBeforeRetry() throws Exception {
        Path root = directory(); FailingIO io = new FailingIO(); Submission saved;
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root, Clock.systemUTC(),
                FileOjSubmissionRepository.LegacyPolicy.REJECT, io)) {
            saved = submit(service(repository), "survivor"); io.failWrite = true;
            assertThrows(IOException.class, () -> submit(service(repository), "not-yet-saved"));
        }
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
            OjSubmissionService service = service(repository);
            assertEquals(Collections.singletonList(saved), service.history(OWNER, null, 0, 100));
            assertNull(service.find(OWNER, OjFingerprints.id(OWNER, "not-yet-saved")));
            assertEquals(2, submit(service, "not-yet-saved").getAttemptNumber());
        }
    }

    @Test void failedRecoveryKeepsRunningRecordForNextRestart() throws Exception {
        Path root = directory(); Submission saved;
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) { saved = submit(service(repository), "recover"); }
        saved.setStatus(TaskStatus.RUNNING); rewrite(root, saved, true);
        byte[] running = Files.readAllBytes(recordPath(root, saved));
        FailingIO io = new FailingIO(); io.failMove = true;
        assertThrows(IOException.class, () -> new FileOjSubmissionRepository(root, Clock.systemUTC(),
                FileOjSubmissionRepository.LegacyPolicy.REJECT, io));
        assertArrayEquals(running, Files.readAllBytes(recordPath(root, saved)));
        try (FileOjSubmissionRepository repository = new FileOjSubmissionRepository(root)) {
            Submission recovered = service(repository).find(OWNER, saved.getSubmissionId());
            assertEquals(TaskStatus.INTERRUPTED, recovered.getStatus());
            assertEquals(1, recovered.getAttemptNumber()); assertEquals(SOURCE, recovered.getSourceCode());
            assertEquals(FileOjSubmissionRepository.recordChecksum(recovered), recovered.getRecordChecksum());
        }
    }

    @Test void concurrencyTimeoutFailsAndTerminatesExecutor() throws Exception {
        ExecutorService pool = newPool(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            AssertionError failure = assertThrows(AssertionError.class, () -> concurrentResults(pool,
                    Arrays.asList(() -> { release.await(); return 1; }, () -> { release.await(); return 2; }), 100));
            assertTrue(failure.getMessage().contains("timed out"));
        } finally { release.countDown(); stopPool(pool); }
        assertTrue(pool.isTerminated());
    }

    @Test void concurrencyExceptionTerminatesExecutor() throws Exception {
        ExecutorService pool = newPool(2);
        try {
            assertThrows(ExecutionException.class, () -> concurrentResults(pool,
                    Collections.singletonList(() -> { throw new IOException("Injected task exception"); }), 1000));
        } finally { stopPool(pool); }
        assertTrue(pool.isTerminated());
    }

    private static <T> List<T> concurrentResults(ExecutorService pool, List<Callable<T>> tasks, long timeoutMs) throws Exception {
        List<T> results = new ArrayList<>();
        for (Future<T> future : pool.invokeAll(tasks, timeoutMs, TimeUnit.MILLISECONDS)) {
            assertFalse(future.isCancelled(), "Concurrent task timed out");
            results.add(future.get(1, TimeUnit.SECONDS));
        }
        return results;
    }

    private static void stopPool(ExecutorService pool) throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Executor did not terminate within 5 seconds");
    }

    private static ExecutorService newPool(int size) {
        return Executors.newFixedThreadPool(size, task -> {
            Thread thread = new Thread(task, "oj-check-worker");
            thread.setDaemon(true); // A failed cleanup assertion must not leave the check JVM hanging forever.
            return thread;
        });
    }

    private static Path recordPath(Path root, Submission record) { return root.resolve(record.getSubmissionId() + ".json"); }

    private static void rewrite(Path root, Submission record, boolean seal) throws IOException {
        if (seal) record.setRecordChecksum(FileOjSubmissionRepository.recordChecksum(record));
        new ObjectMapper().writeValue(recordPath(root, record).toFile(), record);
    }

    private static void writeLegacy(Path root, Submission record) throws IOException {
        record.setSchemaVersion(1); record.setRecordChecksum(null);
        com.fasterxml.jackson.databind.node.ObjectNode json = new ObjectMapper().valueToTree(record);
        json.remove("recordChecksum"); // Match the actual old format, where this field did not exist.
        new ObjectMapper().writeValue(recordPath(root, record).toFile(), json);
    }

    private static FileOjSubmissionRepository migratingRepository(Path root) throws IOException {
        return new FileOjSubmissionRepository(root, Clock.systemUTC(),
                FileOjSubmissionRepository.LegacyPolicy.MIGRATE_AFTER_EXTERNAL_VERIFICATION);
    }

    private static class FailingIO extends FileOjSubmissionRepository.PersistenceIO {
        boolean failWrite, failMove;
        Path lastTemporary;
        int lastWrittenBytes;
        @Override void writeAndForce(Path temporary, byte[] data) throws IOException {
            lastTemporary = temporary;
            if (failWrite) {
                failWrite = false; lastWrittenBytes = Math.min(16, data.length);
                Files.write(temporary, Arrays.copyOf(data, lastWrittenBytes));
                throw new IOException("Injected partial write failure");
            }
            super.writeAndForce(temporary, data); lastWrittenBytes = data.length;
        }
        @Override void atomicMove(Path temporary, Path destination) throws IOException {
            if (failMove) { failMove = false; throw new AtomicMoveNotSupportedException(temporary.toString(), destination.toString(), "Injected atomic move failure"); }
            super.atomicMove(temporary, destination);
        }
    }

    @Test void acceptsSingleRootAndTrailingWhitespace() throws Exception {
        for (String suffix : Arrays.asList("", " \r\n\t \n")) {
            Path root = directory(); Submission saved;
            try (FileOjSubmissionRepository repo = new FileOjSubmissionRepository(root)) { saved = submit(service(repo), "single"); }
            Files.write(recordPath(root, saved), suffix.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
            try (FileOjSubmissionRepository repo = new FileOjSubmissionRepository(root)) {
                assertEquals(saved, submit(service(repo), "single"));
                assertEquals(saved, service(repo).find(OWNER, saved.getSubmissionId()));
                assertEquals(2, submit(service(repo), "another").getAttemptNumber());
                assertEquals(2, service(repo).history(OWNER, null, 0, 100).size());
            }
        }
    }

    @Test void rejectsTrailingObjectsAndGarbageDuringReads() throws Exception {
        for (String suffix : Arrays.asList("\n{}", "\nINVALID_TEXT", "\n[]", "\nnull")) {
            Path root = directory();
            try (FileOjSubmissionRepository repo = new FileOjSubmissionRepository(root)) {
                Submission saved = submit(service(repo), "trailing");
                Files.write(recordPath(root, saved), suffix.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
                byte[] before = Files.readAllBytes(recordPath(root, saved));
                assertThrows(IOException.class, () -> service(repo).find(OWNER, saved.getSubmissionId()));
                assertThrows(IOException.class, () -> service(repo).history(OWNER, null, 0, 100));
                assertThrows(IOException.class, () -> submit(service(repo), "trailing"));
                assertArrayEquals(before, Files.readAllBytes(recordPath(root, saved)));
            }
        }
    }

    @Test void rejectsTrailingDataBeforeRecoveryWithoutChangingFiles() throws Exception { verifyTrailingStartup(false); }

    @Test void rejectsTrailingDataBeforeMigrationWithoutChangingFiles() throws Exception { verifyTrailingStartup(true); }

    private void verifyTrailingStartup(boolean legacy) throws Exception {
        for (String suffix : Arrays.asList("\n{}", "\nINVALID_TEXT")) {
            Path root = directory(); Submission first, second;
            try (FileOjSubmissionRepository repo = new FileOjSubmissionRepository(root)) {
                first = submit(service(repo), "first"); second = submit(service(repo), "second");
            }
            first.setStatus(TaskStatus.RUNNING); second.setStatus(TaskStatus.RUNNING);
            if (legacy) { writeLegacy(root, first); writeLegacy(root, second); }
            else { rewrite(root, first, true); rewrite(root, second, true); }
            Files.write(recordPath(root, second), suffix.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
            byte[] a = Files.readAllBytes(recordPath(root, first)), b = Files.readAllBytes(recordPath(root, second));
            assertThrows(IOException.class, () -> {
                try (FileOjSubmissionRepository ignored = legacy ? migratingRepository(root) : new FileOjSubmissionRepository(root)) { fail("Corrupt record accepted"); }
            });
            assertArrayEquals(a, Files.readAllBytes(recordPath(root, first)));
            assertArrayEquals(b, Files.readAllBytes(recordPath(root, second)));
        }
    }

}
