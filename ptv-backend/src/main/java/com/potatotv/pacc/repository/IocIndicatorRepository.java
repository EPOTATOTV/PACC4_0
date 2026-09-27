package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.IocIndicator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IocIndicatorRepository extends JpaRepository<IocIndicator, Long> {

    Optional<IocIndicator> findByValueAndType(String value, String type);

    /**
     * v5.2 §6.3 归族辅助：同类型下以指定前缀开头的既有 IOC。
     * <p>调用方按固定长度前缀查询，把「同目录/同前缀」的一批指标归到同一族。</p>
     */
    Optional<IocIndicator> findFirstByTypeAndValueStartingWith(String type, String valuePrefix);

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

    /** v5.4 §4.3.3 自动化触发：统计窗口内新入库的 IOC 数量（新型威胁出现即触发规则灰度）。 */
    long countByFirstSeenAfter(Instant since);

    long countBySeverityGreaterThanEqual(int severity);

    List<IocIndicator> findAllBySubscribedTrue();
}