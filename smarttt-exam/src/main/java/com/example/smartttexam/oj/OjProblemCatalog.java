package com.example.smartttexam.oj;

import com.example.smartttexam.oj.OjModels.*;
import java.util.List;

/** Only invoked after current student and classroom authorization. Hidden data stays server-side. */
public interface OjProblemCatalog {
    Problem get(Owner owner, String problemId);
    List<Problem> list(Owner owner);
}
