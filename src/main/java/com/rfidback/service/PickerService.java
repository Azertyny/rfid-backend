package com.rfidback.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.domain.Sort.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.PickerEntity;
import com.rfidback.exception.PickerAlreadyExistsException;
import com.rfidback.exception.PickerHasBucketsException;
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

    private static final Sort DEFAULT_SORT = Sort.by(Order.asc("lastname"), Order.asc("firstname"), Order.asc("id"));

    /** Sortable fields (the only names that reach the query) and the tie-breakers that keep pages stable. */
    private static final Map<String, List<String>> SORT_TIE_BREAKERS = Map.of(
            "lastname", List.of("firstname", "id"),
            "firstname", List.of("lastname", "id"),
            "creationDate", List.of("lastname", "firstname", "id"));

    private final PickerRepository pickerRepository;
    private final BucketRepository bucketRepository;

    public PickersPage listPickers(int page, int size, String sort) {
        Pageable pageable = PageRequest.of(page, size, toSort(sort));
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
        String lastname = requiredName(createPicker.getLastname(), "lastname");
        String firstname = requiredName(createPicker.getFirstname(), "firstname");
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

        String lastname = requiredName(updatePicker.getLastname(), "lastname");
        String firstname = requiredName(updatePicker.getFirstname(), "firstname");
        ensureUniqueName(lastname, firstname, pickerId);

        entity.setLastname(lastname);
        entity.setFirstname(firstname);
        entity.setComment(extractComment(updatePicker.getComment()));

        return toPickerModel(pickerRepository.save(entity));
    }

    public void deletePicker(UUID pickerId) {
        PickerEntity entity = loadPicker(pickerId);
        if (bucketRepository.existsByPicker(entity)) {
            throw new PickerHasBucketsException(
                    "Picker %s still has at least one bucket assigned; unassign it first".formatted(pickerId));
        }
        pickerRepository.delete(entity);
    }

    private Sort toSort(String sort) {
        if (!StringUtils.hasText(sort)) {
            return DEFAULT_SORT;
        }
        String[] parts = sort.split(",", -1);
        if (parts.length != 2) {
            throw invalidSort(sort);
        }
        String field = parts[0].trim();
        if (!SORT_TIE_BREAKERS.containsKey(field)) {
            throw invalidSort(sort);
        }
        Direction direction = Direction.fromOptionalString(parts[1].trim()).orElseThrow(() -> invalidSort(sort));

        Sort result = Sort.by(new Order(direction, field));
        for (String tieBreaker : SORT_TIE_BREAKERS.get(field)) {
            result = result.and(Sort.by(Order.asc(tieBreaker)));
        }
        return result;
    }

    private ResponseStatusException invalidSort(String sort) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid sort '%s': expected field,asc|desc with field in lastname, firstname, creationDate"
                        .formatted(sort));
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

    private String requiredName(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "%s must not be blank".formatted(field));
        }
        return value.trim();
    }
}
