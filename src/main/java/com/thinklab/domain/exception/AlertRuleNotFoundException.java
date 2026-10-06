package com.thinklab.domain.exception;

/** Domain Exception: no alert rule with that id in the tenant. RFC 7807 mapping: HTTP 404 Not Found. */
public class AlertRuleNotFoundException extends BusinessException {

    private static final String ERROR_CODE = "ERR-ALR-00404";

    public AlertRuleNotFoundException(String message) {
        super(ERROR_CODE, message);
    }
}
