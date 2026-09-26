package com.rfidback.exception;

import java.util.List;

import com.rfidback.generated.model.TagInOtherBucket;

/**
 * Registering these tags needs a confirmation that was not given: a move from another bucket (spec 003) and/or tags
 * not in the reference list (spec 010). Answered with 409; both lists are always complete.
 */
public class RegistrationNotConfirmedException extends RuntimeException {

    private final List<TagInOtherBucket> tags;
    private final List<String> offListTags;

    public RegistrationNotConfirmedException(List<TagInOtherBucket> tags, List<String> offListTags) {
        super("Some tags need confirmation; resend with moveConfirmed and/or offListConfirmed set to true");
        this.tags = List.copyOf(tags);
        this.offListTags = List.copyOf(offListTags);
    }

    public List<TagInOtherBucket> getTags() {
        return tags;
    }

    public List<String> getOffListTags() {
        return offListTags;
    }
}
