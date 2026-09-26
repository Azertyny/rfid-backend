package com.rfidback.exception;

import java.util.List;

import com.rfidback.generated.model.TagInOtherBucket;

/** Registering these tags would move them from another bucket, and the move was not confirmed (409, spec 003). */
public class RegistrationNotConfirmedException extends RuntimeException {

    private final List<TagInOtherBucket> tags;

    public RegistrationNotConfirmedException(List<TagInOtherBucket> tags) {
        super("Some tags belong to another bucket; confirm the move with moveConfirmed=true");
        this.tags = List.copyOf(tags);
    }

    public List<TagInOtherBucket> getTags() {
        return tags;
    }
}
