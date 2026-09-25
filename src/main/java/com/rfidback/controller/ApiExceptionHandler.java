package com.rfidback.controller;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.rfidback.exception.ReaderBusyException;
import com.rfidback.exception.TagsInOtherBucketsException;
import com.rfidback.generated.model.ReaderBusy;
import com.rfidback.generated.model.TagsInOtherBuckets;

import jakarta.validation.ConstraintViolationException;

/**
 * The generated APIs are {@code @Validated}: an out-of-range query parameter (e.g. {@code size=500}) throws
 * {@link ConstraintViolationException}, which Spring would otherwise answer with 500.
 * An invalid {@code @Valid} body (e.g. a blank scanned uid) gets a {@link ProblemDetail} naming the fields, since
 * Spring Boot's default error body leaves the message out.
 * A {@link ResponseStatusException} thrown by a service keeps its status and gets its reason as the problem's
 * {@code detail}, so a page can show why (e.g. a dashboard period over 31 days, spec 007).
 * Also renders the 409 answers whose body carries data the page needs, which {@code @ResponseStatus} cannot.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException exception) {
        return ResponseEntity.badRequest()
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleInvalidBody(MethodArgumentNotValidException exception) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest()
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .headers(exception.getHeaders())
                .body(exception.getBody());
    }

    @ExceptionHandler(TagsInOtherBucketsException.class)
    public ResponseEntity<TagsInOtherBuckets> handleTagsInOtherBuckets(TagsInOtherBucketsException exception) {
        TagsInOtherBuckets body = new TagsInOtherBuckets();
        body.setMessage(exception.getMessage());
        body.setTags(exception.getTags());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(ReaderBusyException.class)
    public ResponseEntity<ReaderBusy> handleReaderBusy(ReaderBusyException exception) {
        ReaderBusy body = new ReaderBusy();
        body.setMessage(exception.getMessage());
        body.setStartedBy(exception.getStartedBy());
        body.setStartedAt(exception.getStartedAt());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
