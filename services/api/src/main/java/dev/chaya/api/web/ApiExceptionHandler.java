package dev.chaya.api.web;

import dev.chaya.api.processing.InvalidJobTransitionException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** RFC 9457 problem responses with a stable `code`. Security exceptions are left to Spring Security. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(ApiException.class)
    ProblemDetail api(ApiException e) {
        return problem(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(dev.chaya.api.storage.StorageException.class)
    ProblemDetail storage(dev.chaya.api.storage.StorageException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE",
            "Object storage is unavailable. The request was not completed; retry later.");
    }

    /** No database connection (Hikari timeout, refused connection): say so, instead of a generic 500. */
    @ExceptionHandler(org.springframework.dao.DataAccessResourceFailureException.class)
    ProblemDetail databaseUnavailable(org.springframework.dao.DataAccessResourceFailureException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE",
            "The database is unavailable. The request was not completed; retry later.");
    }

    @ExceptionHandler(BadRequestException.class)
    ProblemDetail badRequest(BadRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    @ExceptionHandler(InvalidJobTransitionException.class)
    ProblemDetail invalidTransition(InvalidJobTransitionException e) {
        return problem(HttpStatus.CONFLICT, "INVALID_JOB_TRANSITION", e.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(DataIntegrityViolationException e) {
        // Do not leak SQL or constraint names.
        return problem(HttpStatus.CONFLICT, "CONFLICT", "The request conflicts with existing data or violates an integrity rule.");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setProperty("code", code);
        return p;
    }
}
