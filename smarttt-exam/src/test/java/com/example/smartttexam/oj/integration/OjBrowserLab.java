package com.example.smartttexam.oj.integration;

import com.auth0.jwt.JWT;
import com.example.smartttexam.oj.http.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.servlet.http.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Test-classpath interactive lab ONLY. Never registers with the business application.
 * All database access is to the freshly created, marked database provided by run_mysql.py.
 * No judgement executor, teacher endpoints, legacy HMAC conversion or production policy.
 */
public final class OjBrowserLab {
    @RestController
    public static class Navigation {
        private final OjTokenIssuer issuer;
        private final OjVerifiedIdentity identity;
        private final OjHttpAccess access;
        private final JdbcTemplate sql;
        public Navigation(OjTokenIssuer issuer,OjVerifiedIdentity identity,OjHttpAccess access,javax.sql.DataSource source){
            this.issuer=issuer;this.identity=identity;this.access=access;this.sql=new JdbcTemplate(source);
        }
        private HttpServletRequest bearer(HttpServletRequest request){
            return new HttpServletRequestWrapper(request){
                @Override public String getHeader(String name){
                    return name.equalsIgnoreCase("Authorization") ? "Bearer "+request.getHeader("token") : super.getHeader(name);
                }
            };
        }
        private ResponseEntity<?> ok(Object data){
            Map<String,Object> result=new LinkedHashMap<>();result.put("code",200);result.put("data",data);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        }
        @PostMapping("/api/login") public ResponseEntity<?> login(@RequestBody Map<String,String> input){
            if(!input.keySet().equals(new HashSet<>(Arrays.asList("loginname","pwd","catelog","loginway")))
                    || !"1".equals(input.get("catelog")) || !"1".equals(input.get("loginway")))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            char[] password=input.getOrDefault("pwd","").toCharArray();
            try {
                String token=issuer.login(input.get("loginname"),password);
                Map<String,Object> profile=new LinkedHashMap<>();
                profile.put("userid",JWT.decode(token).getClaim("id").asString());
                profile.put("catelog","1");profile.put("username",input.get("loginname"));
                profile.put("rolename","实验学生");profile.put("homeurl","/homes/studenthome");
                profile.put("token",token);profile.put("currentterm","隔离实验学期");
                return ok(profile);
            } catch(SecurityException e){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);}
            finally{Arrays.fill(password,'\0');input.clear();}
        }
        @GetMapping("/api/coursemangt/course/currenttermId") public ResponseEntity<?> term(){return ok("lab-term");}
        @PostMapping("/api/homes/studenthome") public ResponseEntity<?> classrooms(HttpServletRequest request){
            HttpServletRequest verified=bearer(request);String user=identity.userId(verified);
            List<Map<String,Object>> cards=sql.query("SELECT DISTINCT c.id,c.courseId FROM cm_classroom c JOIN cm_classroom_student m ON m.classroomId=c.id WHERE m.userId=? ORDER BY c.id",(rs,n)->{
                access.resolveClassroom(verified,rs.getString(1));
                Map<String,Object> card=new LinkedHashMap<>();card.put("id",rs.getString(1));
                card.put("classroomName","隔离课堂 "+rs.getString(1));card.put("url",rs.getString(1));return card;
            },user);
            return ok(cards);
        }
        @GetMapping("/api/homes/switchstucourse") public ResponseEntity<?> classroom(HttpServletRequest request,@RequestParam String id){
            com.example.smartttexam.oj.OjModels.Owner owner=access.resolveClassroom(bearer(request),id);
            Map<String,Object> data=new LinkedHashMap<>();data.put("token",request.getHeader("token"));
            data.put("courseChineseName","实验课程 "+owner.getCourseId());return ok(data);
        }
        @PostMapping("/api/homes/studentmenu") public ResponseEntity<?> menu(HttpServletRequest request){
            identity.userId(bearer(request));return ok(Collections.emptyList());
        }
    }
    public static void main(String[] args) throws Exception {
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.INFO);
        if(args.length!=1 || !"YES_DISPOSABLE_LOCAL_ONLY".equals(System.getenv("OJ_LAB_CONFIRM")))
            throw new IllegalArgumentException("Explicit disposable lab required");
        Path root=Paths.get(args[0]).toAbsolutePath();Files.createDirectories(root);
        String port=System.getenv("OJ_LAB_MYSQL_PORT"),db=System.getenv("OJ_LAB_MYSQL_DATABASE");
        if(port==null || !port.matches("[0-9]{4,5}") || db==null || !db.matches("oj_lab_[a-z0-9_]+"))throw new IllegalArgumentException("Invalid lab");
        String url="jdbc:mysql://127.0.0.1:"+port+"/"+db+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=3000&socketTimeout=5000";
        DriverManagerDataSource reader=new DriverManagerDataSource(url,"oj_reader",Objects.requireNonNull(System.getenv("OJ_LAB_READER_PASSWORD")));
        JdbcTemplate admin=new JdbcTemplate(new DriverManagerDataSource(url,"root",Objects.requireNonNull(System.getenv("OJ_LAB_ROOT_PASSWORD"))));
        if(!Collections.singletonList("SMARTTT_OJ_DISPOSABLE_LAB_V1").equals(admin.queryForList("SELECT marker FROM oj_lab_marker",String.class)))throw new IllegalArgumentException("Marker required");
        admin.update("INSERT INTO cm_classroom VALUES ('other-class','other-course','lab-term')");
        admin.update("INSERT INTO cm_classroom_student VALUES ('member-other','other-class','student-a')");
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KeyPair keys=generator.generateKeyPair();
        ObjectMapper json=new ObjectMapper();OjIsolatedServer server=null;
        try {
            server=new OjIsolatedServer(reader,keys,root,null,16777216,true);
            // A second independently scoped question; no online management endpoint.
            Path catalog=root.resolve("catalog.json");
            com.fasterxml.jackson.databind.node.ObjectNode doc=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Files.readAllBytes(catalog));
            com.fasterxml.jackson.databind.node.ObjectNode extra=((com.fasterxml.jackson.databind.node.ObjectNode)doc.path("problems").get(0)).deepCopy();
            extra.put("courseId","other-course");extra.put("classroomId","other-class");
            ((com.fasterxml.jackson.databind.node.ObjectNode)extra.path("problem")).put("problemId","oj-other-scope");
            ((com.fasterxml.jackson.databind.node.ArrayNode)doc.path("problems")).add(extra);
            Files.write(catalog,json.writeValueAsBytes(doc));server.close();
            server=new OjIsolatedServer(reader,keys,root,null,16777216,true);
            Files.write(root.resolve("ready.json"),json.writeValueAsBytes(Collections.singletonMap("port",server.port)));
            // Private ACL directory control file, never exposed as an HTTP route.
            while(!Files.exists(root.resolve("stop"))){
                Path command=root.resolve("command.json");
                if(Files.exists(command)){
                    Map<?,?> c=json.readValue(Files.readAllBytes(command),Map.class);Files.delete(command);
                    String action=String.valueOf(c.get("action"));
                    if("revoke".equals(action))admin.update("DELETE FROM cm_classroom_student WHERE userId='student-a' AND classroomId='lab-class'");
                    else if("restore".equals(action))admin.update("INSERT INTO cm_classroom_student VALUES ('member-restored','lab-class','student-a')");
                    else if("join-bob".equals(action))admin.update("INSERT INTO cm_classroom_student VALUES ('member-bob','lab-class','student-b')");
                    else if("restart".equals(action)){server.close();server=new OjIsolatedServer(reader,keys,root,null,16777216,true);
                        Files.write(root.resolve("ready.json"),json.writeValueAsBytes(Collections.singletonMap("port",server.port)));}
                    else throw new IllegalArgumentException("Unknown private control action");
                    Files.write(root.resolve("command-result.json"),json.writeValueAsBytes(c));
                }
                Thread.sleep(100);
            }
        } finally {if(server!=null)server.close();Files.deleteIfExists(root.resolve("ready.json"));}
    }
}
