package com.example.smartttexam.oj.judge;

import com.example.smartttexam.oj.*;
import com.example.smartttexam.oj.OjModels.*;
import java.io.*;
import java.util.concurrent.*;

/** One bounded worker, no in-memory backlog; disk submissions are the recoverable queue. */
public final class OjJudgeDispatcher implements AutoCloseable {
    private final FileOjSubmissionRepository submissions;
    private final OjJudgeJournal journal;
    private final GoJudgeClient client;
    private final ScheduledExecutorService worker;
    private volatile boolean closed;
    private volatile boolean unhealthy;
    public OjJudgeDispatcher(FileOjSubmissionRepository submissions,OjJudgeJournal journal,GoJudgeClient client) {
        this.submissions=submissions;this.journal=journal;this.client=client;
        worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"oj-judge");t.setDaemon(true);return t;});
    }
    public void start(){worker.scheduleWithFixedDelay(()->{try{tick();}catch(IOException|RuntimeException e){unhealthy=true;}},0,1,TimeUnit.SECONDS);}
    public boolean isUnhealthy(){return unhealthy;}
    /** Also used for deterministic isolated tests; serializes manual and scheduled callers. */
    public synchronized void tick() throws IOException {
        if(closed)return;
        for(Submission submission:submissions.snapshots()) {
            if(closed || Thread.currentThread().isInterrupted())return;
            OjJudgeJournal.Entry entry=journal.read(submission.getSubmissionId());
            if(entry!=null && ("DONE".equals(entry.state)||"FAILED".equals(entry.state)))continue;
            if(entry!=null && entry.attempts>=2){entry.state="FAILED";entry.verdict="SYSTEM_ERROR";journal.write(entry);continue;}
            if(entry==null){entry=new OjJudgeJournal.Entry();entry.submissionId=submission.getSubmissionId();}
            entry.attempts++;entry.state="RUNNING";journal.write(entry);
            try {
                GoJudgeClient.Result result=client.judge(submission);
                entry.verdict=result.verdict.name();entry.timeNs=result.timeNs;entry.memoryBytes=result.memoryBytes;
                entry.state=result.verdict==Verdict.SYSTEM_ERROR?(entry.attempts<2?"RETRY":"FAILED"):"DONE";
            }catch(IOException|RuntimeException error){entry.state=entry.attempts<2?"RETRY":"FAILED";entry.verdict="SYSTEM_ERROR";}
            journal.write(entry);
        }
        unhealthy=false;
    }
    @Override public void close() throws IOException {
        closed=true;worker.shutdownNow();
        try{if(!worker.awaitTermination(125,TimeUnit.SECONDS))throw new IOException("Judge worker did not stop; storage must remain open");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Interrupted closing judge",e);}
    }
}
