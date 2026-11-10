package com.example.smartttexam.oj.http;

import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only parameterized SQL, table/ID mapping grounded in existing SmartTT mappers.
 * Not auto-registered: status AND classroom/term eligibility require an explicit audited policy.
 */
public final class OjJdbcAuthorizationData implements OjAuthorizationData {
    public interface Eligibility {
        boolean student(String userStatus,String studentStatus);
        boolean classroom(String classroomId,String termId);
    }
    private final JdbcTemplate sql;
    private final Eligibility policy;
    public OjJdbcAuthorizationData(DataSource source,Eligibility policy){sql=new JdbcTemplate(Objects.requireNonNull(source));this.policy=Objects.requireNonNull(policy);}
    public List<Student> studentsByUserId(String id) {
        return sql.query("SELECT u.id AS user_id,u.catelog AS category,u.status AS user_status,s.status AS student_status FROM st_users u JOIN sm_student s ON s.usersid=u.id WHERE u.id=?",
            (rs,n)->new Student(rs.getString("user_id"),rs.getString("category"),policy.student(rs.getString("user_status"),rs.getString("student_status"))),id);
    }
    public boolean courseExists(String id){Integer count=sql.queryForObject("SELECT count(1) FROM cm_course WHERE id=?",Integer.class,id);return count!=null && count==1;}
    public List<String> courseIdsByClassroom(String id) {
        return sql.query("SELECT courseId,termId FROM cm_classroom WHERE id=?",(rs,n)->policy.classroom(id,rs.getString("termId"))?rs.getString("courseId"):null,id);
    }
    public int membershipCount(String classroom,String user){Integer count=sql.queryForObject("SELECT count(1) FROM cm_classroom_student WHERE classroomId=? AND userId=?",Integer.class,classroom,user);return count==null?0:count;}
}
