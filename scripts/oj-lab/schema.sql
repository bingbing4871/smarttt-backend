-- Disposable fixture ONLY. This is a mapper contract subset, NOT a production schema export.
-- LAB_ACTIVE and lab-term are explicit laboratory policy values, not inferred business rules.
CREATE TABLE oj_lab_marker(marker VARCHAR(64) NOT NULL);
INSERT INTO oj_lab_marker VALUES ('SMARTTT_OJ_DISPOSABLE_LAB_V1');
CREATE TABLE st_users(id VARCHAR(64) PRIMARY KEY,loginname VARCHAR(128) NOT NULL,pwd VARCHAR(255) NOT NULL,
 catelog VARCHAR(16) NOT NULL,status VARCHAR(32) NOT NULL);
CREATE TABLE sm_student(id VARCHAR(64) PRIMARY KEY,usersid VARCHAR(64) NOT NULL,status VARCHAR(32) NOT NULL);
CREATE TABLE cm_course(id VARCHAR(64) PRIMARY KEY);
CREATE TABLE cm_term(id VARCHAR(64) PRIMARY KEY,begindate DATE NOT NULL,enddate DATE NOT NULL,iscurrentterm VARCHAR(8) NOT NULL);
CREATE TABLE cm_classroom(id VARCHAR(64) PRIMARY KEY,courseId VARCHAR(64) NOT NULL,termId VARCHAR(64) NOT NULL);
CREATE TABLE cm_classroom_student(id VARCHAR(64) PRIMARY KEY,classroomId VARCHAR(64) NOT NULL,userId VARCHAR(64) NOT NULL);
INSERT INTO st_users VALUES ('student-a','alice',@student_password,'1','LAB_ACTIVE'),
 ('student-b','bob',@other_password,'1','LAB_ACTIVE');
INSERT INTO sm_student VALUES ('sm-a','student-a','LAB_ACTIVE'),('sm-b','student-b','LAB_ACTIVE');
INSERT INTO cm_course VALUES ('lab-course'),('other-course');
INSERT INTO cm_term VALUES ('lab-term',DATE_SUB(CURRENT_DATE,INTERVAL 1 DAY),DATE_ADD(CURRENT_DATE,INTERVAL 1 DAY),'1');
INSERT INTO cm_classroom VALUES ('lab-class','lab-course','lab-term');
INSERT INTO cm_classroom_student VALUES ('member-a','lab-class','student-a');
