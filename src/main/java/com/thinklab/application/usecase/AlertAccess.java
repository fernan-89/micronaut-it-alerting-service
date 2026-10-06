package com.thinklab.application.usecase;

import com.thinklab.domain.exception.AlertAccessDeniedException;

/** Alerting is for IT staff (ADR-032): a REQUESTER is refused everywhere. */
final class AlertAccess {

    static final String REQUESTER_ROLE = "REQUESTER";
    static final String SYSTEM_EXECUTOR = "system:alerting";

    private AlertAccess() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    static void requireStaff(String role, String operation) {
        if (REQUESTER_ROLE.equals(role)) {
            throw new AlertAccessDeniedException("A requester cannot " + operation + ": alerting is handled by IT staff.");
        }
    }
}
