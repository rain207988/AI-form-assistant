package com.xzy.common.util;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * 通用响应封装类
 * @param <T>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor

public class Result<T>{

    private Integer code;


    private String message;


    /**
     * 响应数据内容
     */
    private T data;


    /**
     * 状态码枚举
     */

    public enum ResultCode{
        SUCCESS(200,"成功"),

        //客户端报错
        BAD_REQUEST(400,"请求参数错误"),
        UNAUTHORIZED(401,"未授权"),
        FORBIDDEN(403,"权限不足"),
        NOT_FOUND(404,"资源不存在"),
        METHOD_NOT_ALLOWED(405,"方法不允许"),
        GONE(410,"资源永久删除"),
        UNSUPPORTED_MEDIA_TYPE(415,"不支持的媒体类型"),

        //服务端报错
        INTERNAL_SERVER_ERROR(500,"服务器错误"),
        GATEWAY_ERROR(502,"网关报错"),
        NOT_IMPLEMENTED(501,"未实现"),
        SERVICE_UNAVAILABLE(503,"服务不可用"),
        GATEWAY_TIMEOUT(504,"网关超时"),
        ;

        ResultCode(Integer code, String message) {
            this.code = code;
            this.message = message;
        }

        public String getMessage() {
            return message;
        }

        public Integer getCode() {
            return code;
        }

        private final Integer code;
        private final String message;
    }


    /**
     * 自定义成功的响应
     * @param message 响应文案
     * @param data 响应的数据
     * @return 返回一个通用的结果对象
     * @param <T>
     */
    public static <T> Result<T> success(String message, T data){
        return new Result<>(ResultCode.SUCCESS.getCode(), message, data);

    }


    /**
     * 自定义的失败响应
     * @param resultCode 状态码
     * @param message 响应文案
     * @return 通用响应对象
     * @param <T>
     */
    public static <T> Result<T> fail(ResultCode resultCode,String message){
        return new Result<>(resultCode.getCode(),message,null);
    }

    /**
     * 自定义400错误响应,请求参数报错
     * @param message 响应文案
     * @return 通用响应对象
     * @param <T>
     */
    public static <T> Result<T> badRequest(String message){
        return fail(ResultCode.BAD_REQUEST, message);
    }


    /**
     * 服务端报错
     * @param message 响应文案
     * @return 通用响应对象
     * @param <T>
     */
    public static <T> Result<T> serviceError(String message){
        return fail(ResultCode.INTERNAL_SERVER_ERROR, message);
    }
}
