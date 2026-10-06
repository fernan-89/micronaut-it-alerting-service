package com.thinklab.domain.exception;

/** Domain Exception: the health monitor or the incident service could not be reached or refused. The message names the service and never carries its answer. RFC 7807 mapping: HTTP 502 Bad Gateway. */
public class UpstreamUnavailableException extends BusinessException {

    private static final String ERROR_CODE = "ERR-ALR-00502";

    public UpstreamUnavailableException(String message) {
        super(ERROR_CODE, message);
    }
}
