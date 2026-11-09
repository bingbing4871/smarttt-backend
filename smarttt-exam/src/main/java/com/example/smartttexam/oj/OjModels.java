package com.example.smartttexam.oj;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/** Persistence types, not HTTP response types. Hidden test data must not be sent to students. */
public final class OjModels {
    private OjModels() { }

    public enum TaskStatus { WAITING_JUDGE, RUNNING, INTERRUPTED }
    public enum ExecutionMode { NOT_RUN, MOCK, GO_JUDGE }
    public enum Verdict { AC, WA, CE, TLE, MLE, RE, OLE, SYSTEM_ERROR }

    /** Must be constructed by an authenticated, course-authorized caller, never from a request body. */
    @Data public static class Owner {
        private String studentId;
        private String courseId;
        private String classroomId;
        public Owner() { }
        public Owner(String studentId, String courseId, String classroomId) {
            this.studentId = studentId; this.courseId = courseId; this.classroomId = classroomId;
        }
    }

    @Data public static class TestCase {
        private String caseId;
        private String input;
        private String expectedOutput;
        public TestCase() { }
        public TestCase(String id, String input, String expected) {
            this.caseId = id; this.input = input; this.expectedOutput = expected;
        }
    }

    @Data public static class Problem {
        private String problemId;
        private String version;
        private String testVersion;
        private String statement;
        private String language;
        private List<String> kwaIds = new ArrayList<>();
        private long cpuTimeLimitMs;
        private long wallTimeLimitMs;
        private long memoryLimitBytes;
        private long outputLimitBytes;
        private List<TestCase> testCases = new ArrayList<>();
    }

    /** Future judge attempts append here; rejudging must not overwrite previous attempt results. */
    @Data public static class JudgeAttempt {
        private String attemptId;
        private ExecutionMode mode;
        private String executorVersion;
        private String compilerVersion;
        private String startedAt;
        private String finishedAt;
        private String compileOutput;
        private String technicalError;
        private List<CaseResult> cases = new ArrayList<>();
    }

    @Data public static class CaseResult {
        private String caseId;
        private Verdict verdict;
        private String stdout;
        private String stderr;
        private Integer exitCode;
        private Long cpuTimeNs;
        private Long wallTimeNs;
        private Long memoryBytes;
    }

    @Data public static class Submission {
        private int schemaVersion = 2;
        private String recordChecksum;
        private String submissionId;
        private String requestId;
        private String fingerprint;
        private Owner owner;
        private Problem problem;
        private String sourceCode;
        private String sourceSha256;
        private String language;
        private long attemptNumber;
        private String submittedAt;
        private TaskStatus status;
        private ExecutionMode executionMode = ExecutionMode.NOT_RUN;
        private List<JudgeAttempt> judgeAttempts = new ArrayList<>();
    }
}
