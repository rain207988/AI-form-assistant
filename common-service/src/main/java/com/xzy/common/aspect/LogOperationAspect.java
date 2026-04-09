package com.xzy.common.aspect;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xzy.common.annotation.LogOperation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.aspectj.weaver.ast.Var;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * 操作日志切面
 */

@Aspect
@Component
@Slf4j
public class LogOperationAspect {

    //@Autowired
    private ObjectMapper objectMapper;
    /**
     *
     */
    public LogOperationAspect(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // 关键配置
        /**
         * “如果遇到空的 Java 对象（没有任何属性，或者没有公开的 getter 方法），请不要报错，直接翻译成 {} 就行了。”
         */
        this.objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    /**
     * 环绕通知
     */
    @Around("@annotation(com.xzy.common.annotation.LogOperation)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1.访问的接口方法
        MethodSignature signature = (MethodSignature)joinPoint.getSignature();

        Method method = signature.getMethod();
        LogOperation logOperation = method.getAnnotation(LogOperation.class);

        String methodName = method.getName();
        String operation = logOperation.value().isEmpty() ? methodName : logOperation.value();

        //2.获取请求信息
        ServletRequestAttributes attributes = (ServletRequestAttributes)RequestContextHolder.getRequestAttributes();
        HttpServletRequest request =  attributes!=null ? attributes.getRequest() : null;

        //3.获取请求参数


        //4.记录响应参数


        //5,访问耗时
    }

    /**
     * 记录请求参数
     * @param request
     */

    private  void  LogRequest (String operation, ProceedingJoinPoint joinPoint, HttpServletRequest request ){
        //1.请求描述
        StringBuffer logBuilder = new StringBuffer();
        logBuilder.append("[").append("==========开始执行方法==========").append("]请求参数");


        //2.访问路由，和访问方法
        if(request != null){
            logBuilder.append("请求方法名:").append(request.getMethod()).
            append("请求URL:").append(request.getRequestURL());
        }

        //3.请求参数
        String querySting = request.getQueryString();
        if(querySting != null && !querySting.isEmpty()) {
            logBuilder.append(",query=").append(maskSensitiveData(querySting));
        }

        String authorization = request.getHeader("Authorization");
        if(authorization != null) {
            logBuilder.append(",authorization=").append(maskToken(authorization));
        }

    }

    /**
     * 关键字段实现脱敏
     * @param data 原始字符串
     */
    private String maskSensitiveData(String data){
        data = data.replaceAll("\"password\"\\s*:\\s*\"[^\"]*\"", "\"password\":\"***\"");
        data = data.replaceAll("\"passwordHash\"\\s*:\\s*\"[^\"]*\"", "\"passwordHash\":\"***\"");

        // 脱敏token字段
        data = data.replaceAll("\"token\"\\s*:\\s*\"[^\"]*\"", "\"token\":\"***\"");
        data = data.replaceAll("\"authorization\"\\s*:\\s*\"[^\"]*\"", "\"authorization\":\"***\"");

        // 脱敏邮箱
        data = data.replaceAll("\"email\"\\s*:\\s*\"([^\"]*@[^\"]*)\"", "\"email\":\"***\"");

        return data;
    }


    /**
     * 令牌脱敏
     * @return
     */
    public  String maskToken(String token){
        return  "****";
    }
}
