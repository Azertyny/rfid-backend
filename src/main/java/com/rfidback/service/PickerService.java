package com.rfidback.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.PickerEntity;
import com.rfidback.exception.PickerAlreadyExistsException;
import com.rfidback.exception.PickerNotFoundException;
import com.rfidback.generated.model.CreatePicker;
import com.rfidback.generated.model.PageMetadata;
import com.rfidback.generated.model.Picker;
import com.rfidback.generated.model.PickersPage;
import com.rfidback.generated.model.UpdatePicker;
import com.rfidback.repository.BucketRepository;
import com.rfidback.repository.PickerRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PickerService {

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.asc("lastname"), Sort.Order.asc("firstname"));

    private final PickerRepository pickerRepository;
    private final BucketRepository bucketRepository;

    public PickersPage listPickers(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, DEFAULT_SORT);
        Page<PickerEntity> pickerPage = pickerRepository.findAll(pageable);

        List<PickerEntity> pickerEntities = pickerPage.getContent();
        Map<UUID, Integer> bucketNumberByPickerId = pickerEntities.isEmpty()
                ? Map.of()
                : bucketRepository.findAllByPickerIn(pickerEntities).stream()
                        .filter(bucket -> bucket.getPicker() != null)
                        .collect(Collectors.toMap(bucket -> bucket.getPicker().getId(), BucketEntity::getNumber));

        List<Picker> content = pickerEntities.stream()
                .map(entity -> {
                    Picker picker = toPickerModel(entity);
                    Integer bucketNumber = bucketNumberByPickerId.get(entity.getId());
                    picker.setBucketNumber(bucketNumber);
                    return picker;
                })
                .toList();
        PageMetadata metadata = new PageMetadata();
        metadata.setPage(pickerPage.getNumber());
        metadata.setSize(pickerPage.getSize());
        metadata.setTotalElements(Math.toIntExact(pickerPage.getTotalElements()));
        metadata.setTotalPages(pickerPage.getTotalPages());
        metadata.setHasNext(pickerPage.hasNext());
        metadata.setHasPrevious(pickerPage.hasPrevious());

        PickersPage response = new PickersPage();
        response.setContent(content);
        response.setMetadata(metadata);
        return response;
    }

    public Picker createPicker(CreatePicker createPicker) {
        String lastname = sanitize(createPicker.getLastname());
        String firstname = sanitize(createPicker.getFirstname());
        ensureUniqueName(lastname, firstname, null);

        PickerEntity entity = PickerEntity.builder()
                .lastname(lastname)
                .firstname(firstname)
                .comment(extractComment(createPicker.getComment()))
                .build();

        return toPickerModel(pickerRepository.save(entity));
    }

    public Picker getPicker(UUID pickerId) {
        PickerEntity entity = loadPicker(pickerId);
        Picker picker = toPickerModel(entity);
        bucketRepository.findByPicker(entity)
                .ifPresent(bucket -> picker.setBucketNumber(bucket.getNumber()));
        return picker;
    }

    public Picker updatePicker(UUID pickerId, UpdatePicker updatePicker) {
        PickerEntity entity = loadPicker(pickerId);

        String lastname = sanitize(updatePicker.getLastname());
        String firstname = sanitize(updatePicker.getFirstname());
        ensureUniqueName(lastname, firstname, pickerId);

        entity.setLastname(lastname);
        entity.setFirstname(firstname);
        entity.setComment(extractComment(updatePicker.getComment()));

        return toPickerModel(pickerRepository.save(entity));
    }

    public void deletePicker(UUID pickerId) {
        PickerEntity entity = loadPicker(pickerId);
        pickerRepository.delete(entity);
    }

    private PickerEntity loadPicker(UUID pickerId) {
        return pickerRepository.findById(pickerId)
                .orElseThrow(() -> new PickerNotFoundException("Picker %s not found".formatted(pickerId)));
    }

    private void ensureUniqueName(String lastname, String firstname, UUID currentPickerId) {
        boolean exists = currentPickerId == null
                ? pickerRepository.existsByLastnameIgnoreCaseAndFirstnameIgnoreCase(lastname, firstname)
                : pickerRepository.existsByLastnameIgnoreCaseAndFirstnameIgnoreCaseAndIdNot(lastname, firstname,
                        currentPickerId);
        if (exists) {
            throw new PickerAlreadyExistsException("A picker with the same lastname and firstname already exists");
        }
    }

    private Picker toPickerModel(PickerEntity entity) {
        Picker picker = new Picker();
        picker.setId(entity.getId());
        picker.setLastname(entity.getLastname());
        picker.setFirstname(entity.getFirstname());
        picker.setCreationDate(entity.getCreationDate());
        picker.setComment(entity.getComment());
        return picker;
    }

    private String extractComment(String comment) {
        if (comment == null) {
            return null;
        }
        String trimmed = comment.trim();
        return StringUtils.hasText(trimmed) ? trimmed : null;
    }

    private String sanitize(String value) {
        return value == null ? null : value.trim();
    }
}
