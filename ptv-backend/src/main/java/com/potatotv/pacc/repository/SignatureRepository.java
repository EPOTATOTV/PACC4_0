package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.Signature;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SignatureRepository extends JpaRepository<Signature, String> {

    List<Signature> findByEditionAndState(Signature.Edition edition, String state);

    List<Signature> findByEdition(Signature.Edition edition);

    /** 版本大于 givenVersion 的特征（v4.7 增量 diff 依据）。 */
    List<Signature> findByEditionAndVersionGreaterThan(Signature.Edition edition, long version);

    long countByState(String state);

    @Modifying
    @Query("update Signature s set s.state = :toState, s.version = s.version + 1, "
            + "s.updatedAt = :updatedAt where s.state = :fromState and s.edition = :edition")
    int markState(@Param("fromState") String fromState,
                  @Param("toState") String toState,
                  @Param("edition") Signature.Edition edition,
                  @Param("updatedAt") java.time.Instant updatedAt);
}