package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.IocIndicator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface IocIndicatorRepository extends JpaRepository<IocIndicator, Long> {

    Optional<IocIndicator> findByValueAndType(String value, String type);

    Page<IocIndicator> findByState(String state, Pageable pageable);

    /** 全字段模糊检索（value / source_family / source_id / type）。 */
    @Query("select i from IocIndicator i where " +
            "(:q is null or i.value like %:q% or i.sourceFamily like %:q% or i.sourceId like %:q%)" +
            "and (:type is null or i.type = :type)" +
            "and (:state is null or i.state = :state)")
    Page<IocIndicator> search(@Param("q") String q, @Param("type") String type,
                              @Param("state") String state, Pageable pageable);

    long countByState(String state);

    long countBySubscribedTrue();

    long countBySeverityGreaterThanEqual(int severity);

    List<IocIndicator> findAllBySubscribedTrue();
}