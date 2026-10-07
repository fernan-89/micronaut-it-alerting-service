package com.thinklab.domain.exception;

/** Domain Exception: no maintenance window with that id in the tenant. RFC 7807 mapping: HTTP 404 Not Found. */
public class MaintenanceWindowNotFoundException extends BusinessException {

    private static final String ERROR_CODE = "ERR-ALR-00404";

    public MaintenanceWindowNotFoundException(String message) {
        super(ERROR_CODE, message);
    }
}
