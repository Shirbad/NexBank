package com.nexbank.notification.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final JdbcTemplate jdbc;
    public NotificationController(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @GetMapping
    public List<NotificationView> list(@RequestHeader("X-User-Id") UUID userId,
                                       @RequestParam(defaultValue="0") int page,
                                       @RequestParam(defaultValue="20") int size){
        int boundedSize=Math.max(1,Math.min(size,100)); long offset=(long)Math.max(0,page)*boundedSize;
        return jdbc.query("select id,user_id,message,status,created_at from notifications where user_id=? order by created_at desc limit ? offset ?",
                (rs,row)->new NotificationView(UUID.fromString(rs.getString("id")),UUID.fromString(rs.getString("user_id")),rs.getString("message"),rs.getString("status"),rs.getTimestamp("created_at").toInstant()),
                userId,boundedSize,offset);
    }

    public record NotificationView(UUID id,UUID userId,String message,String status,Instant createdAt){}
}
