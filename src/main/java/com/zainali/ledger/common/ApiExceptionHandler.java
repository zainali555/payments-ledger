package com.zainali.ledger.common;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The single place that turns exceptions into RFC 7807 {@link ProblemDetail} responses with a stable {@code code}.
 * Spring MVC's own exceptions (malformed JSON, bad path variable, unsupported media type, ...) go through
 * {@link #handleExceptionInternal}, so they get a code too.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	static final String VALIDATION_FAILED = "VALIDATION_FAILED";
	static final String NOT_FOUND = "NOT_FOUND";
	static final String FORBIDDEN = "FORBIDDEN";
	static final String INTERNAL_ERROR = "INTERNAL_ERROR";

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(NotFoundException.class)
	ResponseEntity<Object> handleNotFound(NotFoundException ex, WebRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Resource not found");
		problem.setProperty("code", NOT_FOUND);
		return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.NOT_FOUND, request);
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied");
		problem.setProperty("code", FORBIDDEN);
		return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.FORBIDDEN, request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
		log.error("Unhandled exception", ex);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
		problem.setProperty("code", INTERNAL_ERROR);
		return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Request validation failed");
		List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
				.toList();
		problem.setProperty("errors", errors);
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = body instanceof ProblemDetail existing ? existing
				: ex instanceof ErrorResponse errorResponse ? errorResponse.getBody()
				: ProblemDetail.forStatus(status);
		if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
			problem.setProperty("code", codeFor(status));
		}
		return super.handleExceptionInternal(ex, problem, headers, status, request);
	}

	/** Spring MVC's own 4xx errors are all client input problems; anything else keeps its status name. */
	private static String codeFor(HttpStatusCode status) {
		if (status.value() == HttpStatus.NOT_FOUND.value()) {
			return NOT_FOUND;
		}
		if (status.is4xxClientError()) {
			return status.value() == HttpStatus.FORBIDDEN.value() ? FORBIDDEN : VALIDATION_FAILED;
		}
		return INTERNAL_ERROR;
	}

	record FieldViolation(String field, String message) {
	}

}
