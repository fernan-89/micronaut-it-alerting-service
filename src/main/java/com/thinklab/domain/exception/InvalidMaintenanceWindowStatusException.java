package com.thinklab.domain.exception;

/** Domain Exception: an illegal MaintenanceWindow transition (cancelling a cancelled window), or it changed while the write was applied. RFC 7807 mapping: HTTP 409 Conflict. */
public class InvalidMaintenanceWindowStatusException extends BusinessException {

    private static final String ERROR_CODE = "ERR-ALR-00409";

    public InvalidMaintenanceWindowStatusException(String message) {
        super(ERROR_CODE, message);
    }
}
