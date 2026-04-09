package com.xzy.common.annotation;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志的注解，非侵入
 * @author xzy
 * @date 2023/7/10 10:09
 */

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LogOperation {

    /**
     * 接口的名称,用于标识业务的含义
     * @return
     */
    String value() default "";// 日志的值

    /**
     * 是否需要记录请求参数
     * @return
     */
    boolean logRequest() default true;// 是否记录请求参数

    /**
     * 是否需要记录返回参数
     * @return
     */
    boolean logResponse() default true;


    /**
     * 是否记录接口的调用耗时
     * @return
     */
    boolean logExecutionTime() default true;
}
