package com.nexbank.auth.exception;
import org.springframework.http.*;import org.springframework.security.authentication.BadCredentialsException;import org.springframework.web.bind.MethodArgumentNotValidException;import org.springframework.web.bind.annotation.*;import java.time.Instant;import java.util.Map;
@RestControllerAdvice public class ApiExceptionHandler {
 @ExceptionHandler(BadCredentialsException.class) ResponseEntity<?> unauthorized(){return response(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIALS","Invalid email or password");}
 @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> conflict(IllegalArgumentException e){return response(HttpStatus.CONFLICT,"CONFLICT",e.getMessage());}
 @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<?> invalid(){return response(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Request validation failed");}
 @ExceptionHandler(Exception.class) ResponseEntity<?> internal(Exception e){return response(HttpStatus.INTERNAL_SERVER_ERROR,"INTERNAL_ERROR","Request could not be completed");}
 private ResponseEntity<?> response(HttpStatus status,String code,String message){return ResponseEntity.status(status).body(Map.of("timestamp",Instant.now(),"status",status.value(),"code",code,"message",message));}
}
