package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.Signature;
import com.potatotv.pacc.service.SignatureLibraryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 特征库管理：列表 / 新增 / 灰度发布 / 回滚。
 */
@RestController
@RequestMapping("/api/admin/signatures")
@RequiredArgsConstructor
public class SignatureController {

    private final SignatureLibraryService service;

    @GetMapping
    public List<Signature> list(@RequestParam(defaultValue = "BEDROCK") String edition,
                                @RequestParam(required = false) String state) {
        return service.listByEdition(Signature.Edition.valueOf(edition.toUpperCase()), state);
    }

    @PostMapping
    public ResponseEntity<?> add(@RequestBody Map<String, String> body) {
        try {
            Signature.Edition edition = Signature.Edition.valueOf(body.get("edition").toUpperCase());
            return ResponseEntity.ok(service.add(body.get("name"), body.get("pattern"),
                    Integer.parseInt(body.get("risk_level")), edition,
                    body.get("library_version"), body.get("operator")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/gray-release")
    public ResponseEntity<?> gray(@RequestBody Map<String, String> body) {
        try {
            Signature.Edition edition = Signature.Edition.valueOf(body.get("edition").toUpperCase());
            service.grayRelease(edition, Integer.parseInt(body.get("percent")), body.get("operator"));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/diff")
    public Map<String, Object> diff(@RequestParam(defaultValue = "BEDROCK") String edition,
                                    @RequestParam(required = false) Long afterVersion,
                                    @RequestParam(defaultValue = "false") boolean signed) {
        return service.diff(Signature.Edition.valueOf(edition.toUpperCase()), afterVersion, signed);
    }

    @PostMapping("/auto-rollback")
    public ResponseEntity<?> autoRollback(@RequestBody Map<String, String> body) {
        try {
            Signature.Edition edition = Signature.Edition.valueOf(body.get("edition").toUpperCase());
            double fpr = Double.parseDouble(body.get("false_positive_rate"));
            // 超阈值（误报>0.5%）时回滚到上一稳定，否则保持现状
            int n = service.autoRollback(edition, fpr, body.get("operator"));
            return ResponseEntity.ok(Map.of("ok", true, "rolled_back", n));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/rollback")
    public ResponseEntity<?> rollback(@RequestBody Map<String, String> body) {
        try {
            Signature.Edition edition = Signature.Edition.valueOf(body.get("edition").toUpperCase());
            int n = service.rollback(edition, body.get("operator"));
            return ResponseEntity.ok(Map.of("ok", true, "rolled_back", n));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}