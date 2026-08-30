package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.repository.AccountRepository;
import com.potatotv.pacc.repository.DetectionEventRepository;
import com.potatotv.pacc.repository.DeviceRecordRepository;
import com.potatotv.pacc.repository.PeripheralRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 玩家账号服务单元测试：注册字段校验 / 多凭证登录 / 密码强度 / 重置令牌。
 * 仅覆盖纯业务逻辑，存储层以 Mock 代替。
 */
class AccountServiceTest {

    private AccountRepository accountRepository;
    private AccountService service;

    private static final String EMAIL = "tester@ptv.dev";
    private static final String PASSWORD = "Abc123!@#";
    private Account saved;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        DetectionEventRepository evtRepo = mock(DetectionEventRepository.class);
        DeviceRecordRepository devRepo = mock(DeviceRecordRepository.class);
        PeripheralRepository perRepo = mock(PeripheralRepository.class);
        PteidGenerator pteidGenerator = mock(PteidGenerator.class);
        TokenService tokenService = mock(TokenService.class);
        when(pteidGenerator.generate()).thenReturn("PTEID-TEST-0001");
        when(tokenService.createToken(any(String.class), anyBoolean()))
                .thenReturn(new TokenService.Token("PTEID-TEST-0001", "jwt", 0));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            saved = inv.getArgument(0);
            return saved;
        });
        when(accountRepository.findByEmail(EMAIL)).thenAnswer(inv -> Optional.ofNullable(saved));
        when(accountRepository.findByPhone("13800138000")).thenAnswer(inv -> Optional.ofNullable(saved));
        when(accountRepository.findByMcid("TesterMC")).thenAnswer(inv -> Optional.ofNullable(saved));
        when(accountRepository.findByEcid("ECIDTEST01")).thenAnswer(inv -> Optional.ofNullable(saved));
        when(accountRepository.findByQq("100200300")).thenAnswer(inv -> Optional.ofNullable(saved));
        service = new AccountService(accountRepository, evtRepo, devRepo, perRepo,
                pteidGenerator, tokenService);
    }

    private Account registerValid() {
        return service.register(EMAIL, "13800138000", "TesterMC", "ECIDTEST01",
                "100200300", null, PASSWORD, "fp-1");
    }

    @Test
    void registerRejectsBadEmail() {
        assertThrows(IllegalArgumentException.class, () ->
                service.register("not-an-email", "13800138000", "TesterMC", "E1", "100200300",
                        null, PASSWORD, "fp-1"));
    }

    @Test
    void registerRejectsBadPhone() {
        assertThrows(IllegalArgumentException.class, () ->
                service.register(EMAIL, "12345", "TesterMC", "E1", "100200300", null, PASSWORD, "fp-1"));
    }

    @Test
    void registerRejectsBadMcidAndQq() {
        assertThrows(IllegalArgumentException.class, () ->
                service.register(EMAIL, "13800138000", "mc", "E1", "100200300", null, PASSWORD, "fp-1"));
        assertThrows(IllegalArgumentException.class, () ->
                service.register(EMAIL, "13800138000", "TesterMC", "E1", "123", null, PASSWORD, "fp-1"));
    }

    @Test
    void registerRejectsWeakPassword() {
        // 缺少大写 / 特殊符号
        assertThrows(IllegalArgumentException.class, () ->
                service.register(EMAIL, "13800138000", "TesterMC", "E1", "100200300",
                        null, "abc12345", "fp-1"));
    }

    @Test
    void registerRejectsBadNeteaseUuid() {
        assertThrows(IllegalArgumentException.class, () ->
                service.register(EMAIL, "13800138000", "TesterMC", "E1", "100200300",
                        "not-a-uuid", PASSWORD, "fp-1"));
    }

    @Test
    void registerValidSavesAllFields() {
        Account a = registerValid();
        assertEquals(EMAIL, a.getEmail());
        assertEquals("13800138000", a.getPhone());
        assertEquals("TesterMC", a.getMcid());
        assertEquals("ECIDTEST01", a.getEcid());
        assertEquals("100200300", a.getQq());
        assertNotEquals(PASSWORD, a.getPasswordHash()); // 密码为 Argon2 哈希，绝不落明文
    }

    @Test
    void loginByEachCredential() {
        registerValid();
        // 邮箱 / 手机号 / MCID / ECID / QQ 任一凭证均能命中并签发令牌
        TokenService.Token byEmail = service.login(EMAIL, PASSWORD, "fp-1", false);
        TokenService.Token byPhone = service.login("13800138000", PASSWORD, "fp-1", false);
        TokenService.Token byMcid = service.login("TesterMC", PASSWORD, "fp-1", false);
        TokenService.Token byEcid = service.login("ECIDTEST01", PASSWORD, "fp-1", false);
        TokenService.Token byQq = service.login("100200300", PASSWORD, "fp-1", false);
        assertTrue(byEmail != null);
        assertTrue(byPhone != null);
        assertTrue(byMcid != null);
        assertTrue(byEcid != null);
        assertTrue(byQq != null);
    }

    @Test
    void loginWithWrongPasswordThrows() {
        registerValid();
        assertThrows(IllegalArgumentException.class,
                () -> service.login(EMAIL, "WrongPassword1!", "fp-1", false));
    }

    @Test
    void resetTokenFlow() {
        registerValid();
        when(accountRepository.findByResetTokenHash(any(String.class)))
                .thenAnswer(inv -> Optional.ofNullable(saved));
        String token = service.createPasswordResetToken(EMAIL);
        assertTrue(token != null && !token.isEmpty());
        assertDoesNotThrow(() -> service.resetPassword(token, "NewPass123!"));
    }

    @Test
    void registerRejectsDuplicateMcid() {
        Account other = mock(Account.class);
        when(accountRepository.findByMcid("DupMC")).thenReturn(Optional.of(other));
        when(accountRepository.findByQq("100200300")).thenReturn(Optional.empty());
        when(accountRepository.existsByEmail("dup@ptv.dev")).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () ->
                service.register("dup@ptv.dev", "13800138001", "DupMC", "E2", "100200300",
                        null, PASSWORD, "fp-1"));
    }
}