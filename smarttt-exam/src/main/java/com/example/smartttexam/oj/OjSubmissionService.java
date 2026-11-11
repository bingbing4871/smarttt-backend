package com.example.smartttexam.oj;

import com.example.smartttexam.oj.OjModels.*;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Not auto-registered: enable only through an explicitly isolated OJ configuration in a later step. */
public final class OjSubmissionService {
    private final OjSubmissionRepository repository;
    private final OjProblemCatalog catalog;
    public OjSubmissionService(OjSubmissionRepository repository, OjProblemCatalog catalog) {
        this.repository = Objects.requireNonNull(repository); this.catalog = Objects.requireNonNull(catalog);
    }
    public Submission submit(Owner authorizedOwner, String requestId, String problemId, String language,
                             String sourceCode) throws IOException {
        validateOwner(authorizedOwner);
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("Request ID must contain 1-128 letters, digits, hyphens or underscores");
        if (sourceCode == null || sourceCode.trim().isEmpty()
                || sourceCode.indexOf('\0') >= 0 || OjFingerprints.utf8(sourceCode).length > 256 * 1024)
            throw new IllegalArgumentException("Source code must be nonempty UTF-8 text of at most 256 KiB");
        // Retry against the saved immutable snapshot, even after a problem is revised or unpublished.
        Submission previous=repository.find(authorizedOwner,OjFingerprints.id(authorizedOwner,requestId));
        Problem problem;
        if(previous!=null) {
            if(!Objects.equals(problemId,previous.getProblem().getProblemId()))
                throw new IllegalArgumentException("Request ID already used with different submission content");
            problem=previous.getProblem();
        } else problem=catalog.get(authorizedOwner,problemId);
        if (!problem.getLanguage().equals(language)) throw new IllegalArgumentException("Only cpp17 is supported");
        Owner snapshot = new Owner(authorizedOwner.getStudentId(), authorizedOwner.getCourseId(), authorizedOwner.getClassroomId());
        return repository.create(snapshot, requestId, problem, sourceCode, language);
    }
    public Submission find(Owner authorizedOwner, String submissionId) throws IOException {
        validateOwner(authorizedOwner); return repository.find(authorizedOwner, submissionId);
    }
    public List<Submission> history(Owner authorizedOwner, String problemId, int offset, int limit) throws IOException {
        validateOwner(authorizedOwner);
        // Historical snapshots remain queryable after unpublishing. Ownership is always enforced.
        if (problemId != null && !problemId.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("Invalid problem ID");
        return repository.history(authorizedOwner, problemId, offset, limit);
    }
    public List<Problem> problems(Owner owner) { validateOwner(owner); return catalog.list(owner); }
    private void validateOwner(Owner owner) {
        if (owner == null) throw new IllegalArgumentException("Authorized owner is required");
        for (String value : new String[]{owner.getStudentId(), owner.getCourseId(), owner.getClassroomId()})
            if (value == null || value.trim().isEmpty() || value.length() > 128)
                throw new IllegalArgumentException("Valid student, course and classroom scope is required");
    }
}
