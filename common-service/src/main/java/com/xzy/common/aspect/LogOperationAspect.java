package com.xzy.common.aspect;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xzy.common.annotation.LogOperation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import java.util.StringTokenizer;

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
    public Object logOperation(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1.访问的接口方法

        Long startTime = System.currentTimeMillis();

        MethodSignature signature = (MethodSignature)joinPoint.getSignature();
        Method method = signature.getMethod();
        LogOperation logOperation = method.getAnnotation(LogOperation.class);

        //拿到接口的描述,没有就用接口名字代替
        String methodName = method.getName();
        String operation = logOperation.value().isEmpty() ? methodName : logOperation.value();

        //2.获取请求信息
        ServletRequestAttributes attributes = (ServletRequestAttributes)RequestContextHolder.getRequestAttributes();
        HttpServletRequest request =  attributes!=null ? attributes.getRequest() : null;

        //3.获取请求参数
        if(logOperation.logRequest())
        LogRequest(operation, joinPoint, request);

        //4.记录响应参数
        Object result = joinPoint.proceed();
        if(logOperation.logResponse()){
            logResponse(operation, result, startTime, logOperation.logExecutionTime());
        }

        //5.访问耗时,放在上面logResponse里面处理了


        //6.最终返回结果
        return result;
    }

    /**
     * 记录请求参数
     * @param request
     */

    private  void  LogRequest (String operation, ProceedingJoinPoint joinPoint, HttpServletRequest request ) {
        //1.请求描述
        StringBuffer logBuilder = new StringBuffer();
        logBuilder.append("[").append("==========开始执行方法==========").append("]请求参数");


        //2.访问路由，和访问方法
        if (request != null) {
            logBuilder.append("请求方法名:").append(request.getMethod()).
                    append("请求URL:").append(request.getRequestURL());


            //3.请求参数
            String querySting = request.getQueryString();
            if (querySting != null && !querySting.isEmpty()) {
                logBuilder.append(",query=").append(maskSensitiveData(querySting));
            }

            String authorization = request.getHeader("Authorization");
            if (authorization != null) {
                logBuilder.append(",authorization=").append(maskToken(authorization));
            }

        }

        Object[] args = joinPoint.getArgs();

        if(args!=null && args.length>0){
            logBuilder.append(",args=[");

            for(int i = 0; i < args.length; i++) {
                if(i>0) logBuilder.append(",");
                Object arg = args[i];
                if(arg == null){
                    logBuilder.append("null");
                }else if(isFile(arg)){
                    //
                    logBuilder.append("name=").append(getFileName(arg));
                }else if(isHttpServletResponse(arg)){
                    logBuilder.append("下载内容，无法打印");
                }else {

                    try {
                        String agrStr = maskSensitiveData(objectMapper.writeValueAsString(arg));
                        logBuilder.append(agrStr);
                    } catch (JsonProcessingException e) {
                       logBuilder.append("获取参数异常");
                    }

                }

            }

            logBuilder.append("]");
        }
        log.info(logBuilder.toString());
    }


    /**
     * 记录响应参数的日志
     * @param operation 操作描述
     * @param result 响应结果
     * @param startTime 开始时间
     * @param logTime 是否记录耗时
     */
    private void logResponse(String operation, Object result , long startTime , boolean logTime) {
        StringBuilder logBuilder = new StringBuilder();
        logBuilder.append("[").append(operation).append("]响应结果: ");

        if(result == null){
            logBuilder.append("null");
        }else{
            //响应结果序列化

            try {
               String resultStr = maskSensitiveData(objectMapper.writeValueAsString(result));
                logBuilder.append(resultStr);
            } catch (JsonProcessingException e) {
               logBuilder.append("响应结果存在异常!");
            }
        }

        //加上访问耗时
        if(logTime){

            logBuilder.append("访问耗时:").append(System.currentTimeMillis() - startTime).append("ms");
        }

        log.info(logBuilder.toString());

    }

    /**
     * 关键字段实现脱敏,不是所有信息都是适合展示的
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
    private String maskToken(String token){
        return  "****";
    }


    /**
     * 判断是否是文件
     * @param object
     * @return
     */
    private Boolean isFile(Object object){
        return object!=null && object.getClass().getName().contains("MultipartFile");

    }

    /**
     * 根据反射对象拿到文件名
     * @param object
     * @return
     */
    private String getFileName(Object object){


    }

    /**
     * 根据反射对象判断是否是HttpServletResponse
     * @param object
     * @return
     */
    private boolean isHttpServletResponse(Object object){
        return object!=null && object.getClass().getName().contains("HttpServletResponse");
    }
}
