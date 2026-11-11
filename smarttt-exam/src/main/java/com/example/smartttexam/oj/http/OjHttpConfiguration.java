package com.example.smartttexam.oj.http;

import com.example.smartttexam.oj.*;
import java.io.IOException;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name="oj.http.enabled", havingValue="true", matchIfMissing=false)
public class OjHttpConfiguration {
    @Bean(destroyMethod="close")
    public FileOjSubmissionRepository ojHttpRepository(OjHttpAccess access,
            @Value("${oj.http.storage-directory}") String directory) throws IOException {
        Path path=Paths.get(directory);
        if (!path.isAbsolute()) throw new IllegalArgumentException("OJ directory must be absolute");
        return new FileOjSubmissionRepository(path);
    }
    @Bean public OjProblemCatalog ojHttpCatalog(@Value("${oj.http.catalog-file:}") String file) throws IOException {
        // Enabling HTTP requires an explicit private published catalog, never the fixed development fixture.
        return FileOjProblemCatalog.load(Paths.get(file));
    }
    @Bean public OjSubmissionService ojHttpService(FileOjSubmissionRepository repository,OjProblemCatalog catalog) {
        return new OjSubmissionService(repository,catalog);
    }
}
