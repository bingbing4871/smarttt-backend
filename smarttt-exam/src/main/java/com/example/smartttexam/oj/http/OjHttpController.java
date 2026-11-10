package com.example.smartttexam.oj.http;

import com.example.smartttexam.oj.*;
import com.example.smartttexam.oj.OjModels.*;
import java.io.IOException;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
@ConditionalOnProperty(name="oj.http.enabled", havingValue="true", matchIfMissing=false)
@RequestMapping("/exam/oj/submissions")
public class OjHttpController {
    private final OjSubmissionService service;
    private final OjHttpAccess access;
    public OjHttpController(OjSubmissionService service, OjHttpAccess access) {
        this.service=Objects.requireNonNull(service); this.access=Objects.requireNonNull(access);
    }
    private Owner owner(HttpServletRequest request,String course,String classroom) {
        for (String value: new String[]{course,classroom})
            if (value==null || value.trim().isEmpty() || value.length()>128)
                throw new IllegalArgumentException("Invalid scope");
        Owner owner=access.authorize(request,course,classroom);
        if (owner==null || owner.getStudentId()==null || owner.getStudentId().trim().isEmpty()
                || !course.equals(owner.getCourseId()) || !classroom.equals(owner.getClassroomId()))
            throw new ResponseStatusException(FORBIDDEN);
        return new Owner(owner.getStudentId(),course,classroom);
    }
    @PostMapping public OjHttpDtos.View submit(@RequestBody OjHttpDtos.Submit body,HttpServletRequest request) throws IOException {
        Owner owner=owner(request,body.courseId,body.classroomId);
        // Check conflict explicitly; leave all persistence/idempotency rules in the reviewed service.
        try { return new OjHttpDtos.View(service.submit(owner,body.requestId,body.problemId,body.language,body.sourceCode)); }
        catch (IllegalArgumentException error) {
            if ("Request ID already used with different submission content".equals(error.getMessage()))
                throw new ResponseStatusException(CONFLICT);
            throw error;
        }
    }
    @GetMapping("/{id}") public OjHttpDtos.View detail(@PathVariable("id") String id,
            @RequestParam("courseId") String course,@RequestParam("classroomId") String classroom,
            HttpServletRequest request) throws IOException {
        Submission record=service.find(owner(request,course,classroom),id);
        if (record==null) throw new ResponseStatusException(NOT_FOUND);
        return new OjHttpDtos.View(record);
    }
    @GetMapping public List<OjHttpDtos.View> history(@RequestParam("courseId") String course,
            @RequestParam("classroomId") String classroom,@RequestParam(value="problemId",required=false) String problem,
            @RequestParam(value="offset",defaultValue="0") int offset,
            @RequestParam(value="limit",defaultValue="20") int limit,HttpServletRequest request) throws IOException {
        Owner owner=owner(request,course,classroom);
        List<OjHttpDtos.View> result=new ArrayList<>();
        for (Submission record:service.history(owner,problem,offset,limit)) result.add(new OjHttpDtos.View(record));
        return result;
    }
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<OjHttpDtos.Error> denied(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatus()).body(new OjHttpDtos.Error(error.getStatus().name()));
    }
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<OjHttpDtos.Error> invalid(Exception error) {
        return ResponseEntity.badRequest().body(new OjHttpDtos.Error("INVALID_INPUT"));
    }
    @ExceptionHandler(IOException.class) public ResponseEntity<OjHttpDtos.Error> storage(IOException error) {
        return ResponseEntity.status(SERVICE_UNAVAILABLE).body(new OjHttpDtos.Error("STORAGE_UNAVAILABLE"));
    }
}
