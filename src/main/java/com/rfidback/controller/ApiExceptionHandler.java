package com.rfidback.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.rfidback.exception.ReaderBusyException;
import com.rfidback.exception.TagsInOtherBucketsException;
import com.rfidback.generated.model.ReaderBusy;
import com.rfidback.generated.model.TagsInOtherBuckets;

import jakarta.validation.ConstraintViolationException;

/**
 * The generated APIs are {@code @Validated}: an out-of-range query parameter (e.g. {@code size=500}) throws
 * {@link ConstraintViolationException}, which Spring would otherwise answer with 500.
 * Also renders the 409 answers whose body carries data the page needs, which {@code @ResponseStatus} cannot.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException exception) {
        return ResponseEntity.badRequest()
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage()));
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
