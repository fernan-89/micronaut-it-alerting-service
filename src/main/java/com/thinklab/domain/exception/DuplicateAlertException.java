package com.thinklab.domain.exception;

/**
 * Domain Exception: Thrown when an Alert is initiated with a serial number that already exists
 * within the same Organisation scope.
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict.
 */
public class DuplicateAlertException extends BusinessException {

    private static final String ERROR_CODE = "ERR-ALR-00409";

    public DuplicateAlertException(String message) {
        super(ERROR_CODE, message);
    }
}
