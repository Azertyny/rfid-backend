package com.rfidback.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.TagEntity;
import com.rfidback.generated.model.ScanTagRequest;
import com.rfidback.generated.model.ScanTagResponse;
import com.rfidback.repository.TagRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TagService {

    private final TagRepository tagRepository;

    public ScanTagResponse registerScan(ReaderEntity reader, ScanTagRequest scanTagRequest) {
        Assert.notNull(reader, "Reader must not be null");

        TagEntity tagEntity = TagEntity.builder()
                .uid(scanTagRequest.getUid())
                .conforme(Boolean.TRUE.equals(scanTagRequest.getConforme()))
                .message(buildMessage(scanTagRequest.getConforme()))
                .reader(reader)
                .build();

        TagEntity saved = tagRepository.save(tagEntity);

        ScanTagResponse response = new ScanTagResponse();
        response.setUid(saved.getUid());
        response.setConforme(saved.isConforme());
        response.setProcessedAt(saved.getProcessedAt());
        response.setMessage(Optional.ofNullable(saved.getMessage()));
        return response;
    }

    private String buildMessage(Boolean conforme) {
        return Boolean.TRUE.equals(conforme) ? "Tag enregistré comme conforme" : "Tag enregistré comme non conforme";
    }
}
