package com.example.smartttexam.oj.http;

import java.util.List;

/** Read-only port. Must query current records on every call, without cached memberships.
 * No production implementation: account status/term rules still require confirmation.
 */
public interface OjAuthorizationData {
    List<Student> studentsByUserId(String userId);
    boolean courseExists(String courseId);
    List<String> courseIdsByClassroom(String classroomId);
    int membershipCount(String classroomId,String userId);

    final class Student {
        public final String userId, category;
        /** True only after authoritative account AND student status rules are verified; unknown must be false. */
        public final boolean eligible;
        public Student(String userId,String category,boolean eligible) {
            this.userId=userId; this.category=category; this.eligible=eligible;
        }
    }
}
