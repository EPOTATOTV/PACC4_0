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

    long countByState(String state);

    @Modifying
    @Query("update Signature s set s.state = :toState where s.state = :fromState and s.edition = :edition")
    int markState(@Param("fromState") String fromState,
                  @Param("toState") String toState,
                  @Param("edition") Signature.Edition edition);
}