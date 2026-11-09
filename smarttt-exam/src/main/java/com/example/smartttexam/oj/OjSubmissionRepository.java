package com.example.smartttexam.oj;

import com.example.smartttexam.oj.OjModels.*;
import java.io.IOException;
import java.util.List;

public interface OjSubmissionRepository extends AutoCloseable {
    Submission create(Owner owner, String requestId, Problem problem, String source, String language) throws IOException;
    Submission find(Owner owner, String submissionId) throws IOException;
    List<Submission> history(Owner owner, String problemId, int offset, int limit) throws IOException;
    @Override void close() throws IOException;
}
