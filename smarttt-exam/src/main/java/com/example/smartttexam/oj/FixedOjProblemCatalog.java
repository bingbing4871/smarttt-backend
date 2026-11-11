package com.example.smartttexam.oj;

import com.example.smartttexam.oj.OjModels.*;
import java.util.Arrays;

/** Development fixture only. No connection to the formal question bank. */
public final class FixedOjProblemCatalog implements OjProblemCatalog {
    public static final String PROBLEM_ID = "oj-fixture-add";
    public Problem get(String problemId) {
        if (!PROBLEM_ID.equals(problemId)) throw new IllegalArgumentException("Unknown fixture problem");
        Problem problem = new Problem();
        problem.setProblemId(PROBLEM_ID); problem.setVersion("1"); problem.setTestVersion("1");
        problem.setStatement("输入两个整数a和b，范围为[-1000000000,1000000000]，输出a+b。使用标准输入输出。");
        problem.setLanguage("cpp17"); problem.setCpuTimeLimitMs(1000); problem.setWallTimeLimitMs(3000);
        problem.setMemoryLimitBytes(128L * 1024 * 1024); problem.setOutputLimitBytes(64L * 1024);
        problem.setTestCases(Arrays.asList(new TestCase("positive", "2 3\n", "5\n"),
                new TestCase("zero", "0 0\n", "0\n"),
                new TestCase("negative", "-8 3\n", "-5\n"),
                new TestCase("boundary", "1000000000 1000000000\n", "2000000000\n")));
        return problem;
    }
    @Override public Problem get(Owner owner,String id) { return get(id); }
    @Override public java.util.List<Problem> list(Owner owner) {
        return java.util.Collections.singletonList(get(PROBLEM_ID));
    }
}
