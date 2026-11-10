package com.example.smartttexam.oj.http;

import com.example.smartttexam.oj.OjModels.Owner;
import javax.servlet.http.HttpServletRequest;

/** No production implementation yet. Must verify identity AND membership on every call.
 * Never implement using request studentId, legacy ThreadLocal or the test-token issuer.
 * Throw 401 for absent/invalid identity, 403 for denied membership, 503 if unavailable.
 */
public interface OjHttpAccess {
    Owner authorize(HttpServletRequest request, String courseId, String classroomId);
}
