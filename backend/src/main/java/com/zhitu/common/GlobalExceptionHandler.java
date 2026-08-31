package com.zhitu.common;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice public class GlobalExceptionHandler {
 private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
 @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
 public ApiResponse<Map<String,Object>> bad(IllegalArgumentException e){ log.warn("请求参数错误：{}", e.getMessage()); return ApiResponse.fail(e.getMessage()); }
 @ExceptionHandler(Exception.class) @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
 public ApiResponse<Map<String,Object>> error(Exception e){ log.error("未处理系统异常", e); return ApiResponse.fail("系统处理失败："+e.getMessage()); }
}
