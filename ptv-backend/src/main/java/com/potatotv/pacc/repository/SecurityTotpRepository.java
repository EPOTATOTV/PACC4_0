package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.SecurityTotp;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SecurityTotpRepository extends JpaRepository<SecurityTotp, String> {
}