package com.thinklab.domain.exception;

/** Domain Exception: an illegal AlertRule lifecycle transition, or the rule changed while the write was applied. RFC 7807 mapping: HTTP 409 Conflict. */
public class InvalidAlertRuleStatusException extends BusinessException {

    private static final String ERROR_CODE = "ERR-ALR-00409";

    public InvalidAlertRuleStatusException(String message) {
        super(ERROR_CODE, message);
    }
}
