package com.plm.common.api;

public class Result<T> {
    private int code;
    private String message;
    private T data;
    public static <T> Result<T> ok(T data){
        Result<T> r = new Result<>();
        r.code = 0; r.message = "success"; r.data = data; return r;
    }
    public int getCode(){return code;}
    public String getMessage(){return message;}
    public T getData(){return data;}
}
