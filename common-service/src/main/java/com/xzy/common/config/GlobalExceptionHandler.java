package com.xzy.common.config;

import com.xzy.common.util.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理
 */

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 请求体不可读异常
     * @param e 异常内容
     * @return 客户端400
     */
    @ExceptionHandler(value = HttpMessageNotReadableException.class)
    public Result<Void> handleJsonException(HttpMessageNotReadableException e){

        return Result.badRequest("请求格式错误");
    }

    /**
     * 参数缺失
     * @param e 异常内容
     * @return 客户端400
     */
    public Result<Void> handleMethodArgsNotValidException(MethodArgumentNotValidException e){
        return Result.badRequest("请求参数缺失");
    }

    /**
     * 上传文件过大
     * @param e 异常内容
     * @return 客户端400
     */
    public Result<Void> uploadSizeExcwption(MaxUploadSizeExceededException e){
        log.warn("文件过大了 ,{}" , e.getMessage());
        return Result.badRequest("文件大小超出限制");
    }


    /**
     * 堆内存溢出
     * @return
     */
    public Result<Void> oomException(){


    }

}
