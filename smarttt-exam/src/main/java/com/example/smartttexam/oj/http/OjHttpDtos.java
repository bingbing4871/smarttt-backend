package com.example.smartttexam.oj.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.example.smartttexam.oj.OjModels.Submission;

public final class OjHttpDtos {
    private OjHttpDtos() { }
    public static class Submit {
        public String requestId;
        public String courseId;
        public String classroomId;
        public String problemId;
        public String language;
        public String sourceCode;
        @JsonAnySetter public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown submission field");
        }
    }
    /** Explicit allowlist. No persistence objects, hidden tests or execution internals. */
    public static class View {
        public final String submissionId, requestId, courseId, classroomId, problemId;
        public final String language, sourceCode, submittedAt, status;
        public final long attemptNumber;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public Judgement judgement;
        public void setJudgement(com.example.smartttexam.oj.judge.OjJudgeJournal.Entry entry) {
            if(entry!=null) judgement=new Judgement(entry.state,entry.verdict,entry.timeNs,entry.memoryBytes);
        }
        public View(Submission record) {
            submissionId=record.getSubmissionId(); requestId=record.getRequestId();
            courseId=record.getOwner().getCourseId(); classroomId=record.getOwner().getClassroomId();
            problemId=record.getProblem().getProblemId(); language=record.getLanguage();
            sourceCode=record.getSourceCode(); submittedAt=record.getSubmittedAt();
            status=record.getStatus().name(); attemptNumber=record.getAttemptNumber();
        }
    }
    public static class Judgement {
        public final String state,verdict;
        public final long timeNs,memoryBytes;
        public Judgement(String state,String verdict,long time,long memory){this.state=state;this.verdict=verdict;timeNs=time;memoryBytes=memory;}
    }
    public static class Error {
        public final String code;
        public Error(String code) { this.code=code; }
    }
}
