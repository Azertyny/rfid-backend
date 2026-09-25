package com.rfidback.exception;

import java.util.List;

import com.rfidback.generated.model.TagInOtherBucket;

/** Registering these tags would move them from another bucket, and the move was not confirmed (409). */
public class TagsInOtherBucketsException extends RuntimeException {

    private final List<TagInOtherBucket> tags;

    public TagsInOtherBucketsException(List<TagInOtherBucket> tags) {
        super("Some tags belong to another bucket; confirm the move with moveConfirmed=true");
        this.tags = List.copyOf(tags);
    }

    public List<TagInOtherBucket> getTags() {
        return tags;
    }
}
