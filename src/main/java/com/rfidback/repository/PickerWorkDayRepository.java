package com.rfidback.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rfidback.entity.PickerEntity;
import com.rfidback.entity.PickerWorkDayEntity;

public interface PickerWorkDayRepository extends JpaRepository<PickerWorkDayEntity, UUID> {

    /** The hours entered for a day, with their picker and author (spec 014, day screen). */
    @EntityGraph(attributePaths = { "picker", "updatedBy" })
    List<PickerWorkDayEntity> findAllByWorkDate(LocalDate workDate);

    List<PickerWorkDayEntity> findAllByWorkDateAndPickerIn(LocalDate workDate, Collection<PickerEntity> pickers);

    /** Minutes per picker over the days {@code [first, last]}, both station days (spec 014, dashboard). */
    @Query("select p.id as pickerId, p.firstname as firstname, p.lastname as lastname, sum(w.minutes) as minutes"
            + " from PickerWorkDayEntity w join w.picker p where w.workDate between :first and :last"
            + " group by p.id, p.firstname, p.lastname")
    List<PickerMinutesView> sumMinutesByPicker(@Param("first") LocalDate first, @Param("last") LocalDate last);

    @Modifying
    @Query("delete from PickerWorkDayEntity w where w.picker = :picker")
    int deleteByPicker(@Param("picker") PickerEntity picker);

    // Number, not Long: sum comes back as Long or BigDecimal depending on the database (see RecordRepository).
    interface PickerMinutesView {
        UUID getPickerId();

        String getFirstname();

        String getLastname();

        Number getMinutes();
    }
}
