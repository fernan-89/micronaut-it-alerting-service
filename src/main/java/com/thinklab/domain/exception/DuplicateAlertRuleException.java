package com.thinklab.domain.exception;

/** Domain Exception: an alert rule name already used in the organisation. RFC 7807 mapping: HTTP 409 Conflict. */
public class DuplicateAlertRuleException extends BusinessException {

    private static final String ERROR_CODE = "ERR-ALR-00409";

    public DuplicateAlertRuleException(String message) {
        super(ERROR_CODE, message);
    }
}
