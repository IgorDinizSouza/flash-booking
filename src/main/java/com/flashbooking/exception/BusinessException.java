package com.flashbooking.exception;

import com.flashbooking.model.enums.ErrorCode;

public class BusinessException extends RuntimeException {

    private final ErrorCode code;

    public BusinessException(ErrorCode code) {
        super(code.defaultMessage());
        this.code = code;
    }

    public BusinessException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
