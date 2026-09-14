package com.pkb.common;

import lombok.Getter;

/**
 * 业务异常：message 直接面向用户（中文）。
 */
@Getter
public class BusinessException extends RuntimeException {
    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = 400;
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
