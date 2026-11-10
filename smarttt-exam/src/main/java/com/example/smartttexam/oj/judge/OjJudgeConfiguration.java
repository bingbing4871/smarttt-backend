package com.example.smartttexam.oj.judge;

import java.net.URI;
import java.nio.file.*;
import java.io.IOException;
import com.example.smartttexam.oj.FileOjSubmissionRepository;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
@ConditionalOnProperty(name="oj.judge.enabled",havingValue="true",matchIfMissing=false)
public class OjJudgeConfiguration {
    @Bean public GoJudgeClient ojGoJudgeClient(@Value("${oj.judge.endpoint}") String endpoint,
            @Value("${oj.judge.bearer-token:}") String token,
            @Value("${oj.judge.compile-artifact-limit-bytes:16777216}") long artifactLimit){
        return new GoJudgeClient(URI.create(endpoint),15000,token,artifactLimit);
    }
    @Bean(destroyMethod="close") public OjJudgeJournal ojJudgeJournal(@Value("${oj.judge.result-directory}") String directory,
            @Value("${oj.http.storage-directory}") String submissions) throws IOException {
        Path results=Paths.get(directory).toAbsolutePath().normalize(),source=Paths.get(submissions).toAbsolutePath().normalize();
        if(!Paths.get(directory).isAbsolute() || results.startsWith(source) || source.startsWith(results))throw new IllegalArgumentException("Separate absolute result directory required");
        return new OjJudgeJournal(results);
    }
    @Bean(initMethod="start",destroyMethod="close") public OjJudgeDispatcher ojJudgeDispatcher(FileOjSubmissionRepository repository,OjJudgeJournal journal,GoJudgeClient client){return new OjJudgeDispatcher(repository,journal,client);}
}
