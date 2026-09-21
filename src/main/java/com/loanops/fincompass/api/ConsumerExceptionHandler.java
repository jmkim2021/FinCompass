package com.loanops.fincompass.api;
import com.loanops.api.dto.ApiDtos.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
@RestControllerAdvice(basePackages="com.loanops.fincompass.api")
public class ConsumerExceptionHandler {
 @ExceptionHandler({IllegalArgumentException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
 public ResponseEntity<ApiErrorResponse> invalid(Exception e,HttpServletRequest request){return ResponseEntity.badRequest().body(new ApiErrorResponse(Instant.now(),400,"Bad Request","Invalid or incomplete financial request",request.getRequestURI()));}
}
