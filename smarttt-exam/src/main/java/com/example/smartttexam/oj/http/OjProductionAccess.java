package com.example.smartttexam.oj.http;

import com.example.smartttexam.oj.OjModels.Owner;
import java.util.List;
import java.util.Objects;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Real authorization policy with injected verified identity/read-only data; intentionally no Bean registration. */
public final class OjProductionAccess implements OjHttpAccess {
    private final OjVerifiedIdentity identity;
    private final OjAuthorizationData data;
    public OjProductionAccess(OjVerifiedIdentity identity,OjAuthorizationData data) {
        this.identity=Objects.requireNonNull(identity); this.data=Objects.requireNonNull(data);
    }
    @Override public Owner resolveClassroom(HttpServletRequest request,String classroom) {
        // Authenticate before querying scope; the same complete membership policy is then applied.
        try {
            String user=identity.userId(request);
            if(user==null || user.trim().isEmpty()) throw new ResponseStatusException(UNAUTHORIZED);
            if(classroom==null || classroom.trim().isEmpty() || classroom.length()>128) throw new ResponseStatusException(FORBIDDEN);
            List<String> courses=data.courseIdsByClassroom(classroom);
            if(courses==null || courses.size()!=1 || courses.get(0)==null) throw new ResponseStatusException(FORBIDDEN);
            return authorize(request,courses.get(0),classroom);
        } catch(ResponseStatusException error) {
            throw new ResponseStatusException(error.getStatus()==UNAUTHORIZED ? UNAUTHORIZED :
                error.getStatus()==FORBIDDEN ? FORBIDDEN : SERVICE_UNAVAILABLE);
        } catch(RuntimeException error) { throw new ResponseStatusException(SERVICE_UNAVAILABLE); }
    }
    @Override public Owner authorize(HttpServletRequest request,String course,String classroom) {
        String userId;
        try { userId=identity.userId(request); }
        catch(ResponseStatusException error) {
            throw new ResponseStatusException(error.getStatus()==UNAUTHORIZED ? UNAUTHORIZED : SERVICE_UNAVAILABLE);
        } catch(RuntimeException error) { throw new ResponseStatusException(SERVICE_UNAVAILABLE); }
        if(userId==null || userId.trim().isEmpty()) throw new ResponseStatusException(UNAUTHORIZED);
        if(course==null || classroom==null || course.trim().isEmpty() || classroom.trim().isEmpty())
            throw new ResponseStatusException(FORBIDDEN);
        try {
            List<OjAuthorizationData.Student> students=data.studentsByUserId(userId);
            if(students==null || students.size()!=1 || students.get(0)==null
                    || !userId.equals(students.get(0).userId) || !"1".equals(students.get(0).category)
                    || !students.get(0).eligible) throw new ResponseStatusException(FORBIDDEN);
            if(!data.courseExists(course)) throw new ResponseStatusException(FORBIDDEN);
            List<String> courses=data.courseIdsByClassroom(classroom);
            if(courses==null || courses.size()!=1 || !course.equals(courses.get(0)))
                throw new ResponseStatusException(FORBIDDEN);
            if(data.membershipCount(classroom,userId)!=1) throw new ResponseStatusException(FORBIDDEN);
            return new Owner(userId,course,classroom);
        } catch(ResponseStatusException error) {
            // Never forward data-source exception reasons or internal diagnostics.
            if(error.getStatus()==FORBIDDEN) throw new ResponseStatusException(FORBIDDEN);
            throw new ResponseStatusException(SERVICE_UNAVAILABLE);
        } catch(RuntimeException error) { throw new ResponseStatusException(SERVICE_UNAVAILABLE); }
    }
}
