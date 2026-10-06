package com.thinklab.domain.exception;

/** Domain Exception: a REQUESTER tried to use alerting (staff only, ADR-032). RFC 7807 mapping: HTTP 403 Forbidden. */
public class AlertAccessDeniedException extends BusinessException {

    private static final String ERROR_CODE = "ERR-ALR-00403";

    public AlertAccessDeniedException(String message) {
        super(ERROR_CODE, message);
    }
}
