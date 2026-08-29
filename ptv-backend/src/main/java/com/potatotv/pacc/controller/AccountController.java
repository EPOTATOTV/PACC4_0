package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 账号查询接口（管理端）：按 PTEID / 邮箱检索。
 * <p>仅返回脱敏视图 {@link AccountView}，绝不外泄密码哈希 / 设备指纹等敏感字段。</p>
 */
@RestController
@RequestMapping("/api/admin/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountRepository accountRepository;

    @GetMapping
    public List<AccountView> list(@RequestParam(required = false) String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return accountRepository.findAll().stream().map(AccountView::from).toList();
        }
        Optional<Account> byId = accountRepository.findById(keyword);
        return byId.map(a -> List.of(AccountView.from(a)))
                .orElseGet(() -> accountRepository.findByEmail(keyword)
                        .map(a -> List.of(AccountView.from(a))).orElse(List.of()));
    }

    /** 脱敏账号视图（管理后台展示用）。 */
    public record AccountView(
            String pteid,
            String email,
            String phone,
            int reputation,
            String status,
            int totalRedscreen,
            Instant registeredAt,
            Instant lastRedScreenTime) {

        static AccountView from(Account a) {
            return new AccountView(a.getPteid(), a.getEmail(), a.getPhone(),
                    a.getReputation(), a.getStatus(), a.getTotalRedscreen(),
                    a.getRegisteredAt(), a.getLastRedScreenTime());
        }
    }
}