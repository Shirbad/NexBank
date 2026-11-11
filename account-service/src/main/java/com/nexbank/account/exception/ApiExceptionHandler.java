package com.nexbank.account.exception;
import jakarta.persistence.EntityNotFoundException;import jakarta.validation.ConstraintViolationException;import org.springframework.http.*;import org.springframework.security.access.AccessDeniedException;import org.springframework.web.bind.MethodArgumentNotValidException;import org.springframework.web.bind.annotation.*;import java.time.Instant;import java.util.Map;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;
@RestControllerAdvice public class ApiExceptionHandler {
 private static final Logger log=LoggerFactory.getLogger(ApiExceptionHandler.class);
 @ExceptionHandler(EntityNotFoundException.class) ResponseEntity<?> notFound(RuntimeException e){return response(HttpStatus.NOT_FOUND,"NOT_FOUND",e.getMessage());}
 @ExceptionHandler(AccessDeniedException.class) ResponseEntity<?> forbidden(RuntimeException e){return response(HttpStatus.FORBIDDEN,"FORBIDDEN",e.getMessage());}
 @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,ConstraintViolationException.class,MethodArgumentNotValidException.class}) ResponseEntity<?> badRequest(Exception e){return response(HttpStatus.BAD_REQUEST,"BAD_REQUEST",e.getMessage());}
 @ExceptionHandler(Exception.class) ResponseEntity<?> internal(Exception e){log.error("Unhandled account-service request failure",e);return response(HttpStatus.INTERNAL_SERVER_ERROR,"INTERNAL_ERROR","Request could not be completed");}
 private ResponseEntity<?> response(HttpStatus status,String code,String message){return ResponseEntity.status(status).body(Map.of("timestamp",Instant.now(),"status",status.value(),"code",code,"message",message==null?"Request failed":message));}
}
