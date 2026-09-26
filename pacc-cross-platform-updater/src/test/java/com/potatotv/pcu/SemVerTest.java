package com.potatotv.pcu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemVerTest {

    private static int cmp(String a, String b) {
        return SemVer.parse(a).compareTo(SemVer.parse(b));
    }

    @Test
    void 主次修订段按数值比较() {
        assertTrue(cmp("5.10.0", "5.9.0") > 0, "字符串序会说 5.10.0 更小");
        assertTrue(cmp("v5.4.1", "5.4.0") > 0, "允许 v 前缀");
        assertEquals(0, cmp("5.4", "5.4.0"), "缺省段按 0 处理");
        assertEquals(0, cmp("5.4.0+build.7", "5.4.0"), "构建元数据不参与比较");
    }

    @Test
    void 预发布版低于正式版() {
        assertTrue(cmp("5.4.0", "5.4.0-rc.1") > 0);
        assertTrue(cmp("5.4.0-alpha", "5.4.0-beta") < 0, "字母标识按字典序");
        assertTrue(cmp("5.4.0-rc.2", "5.4.0-rc.10") < 0, "数字标识按数值");
        assertTrue(cmp("5.4.0-1", "5.4.0-alpha") < 0, "数字标识低于字母标识");
        assertTrue(cmp("5.4.0-rc", "5.4.0-rc.1") < 0, "字段少的一方更小");
    }

    @Test
    void 超长数字标识不再抛溢出异常() {
        // SemVer 对数字标识没有位数上限，这里不能走 Long.parseLong
        long big = 2024010112345678901L;
        String shorter = String.valueOf(big);
        String longer = String.valueOf(big) + "0";

        assertTrue(cmp("5.4.0-rc." + longer, "5.4.0-rc." + shorter) > 0,
                "位数多的数值更大");
        assertEquals(0, cmp("5.4.0-rc.007", "5.4.0-rc.7"), "前导零不影响数值");
    }

    @Test
    void 主版本段溢出给出可读错误() {
        assertThrows(PcuException.class, () -> SemVer.parse("99999999999.0.0"));
        assertNull(SemVer.tryParse("99999999999.0.0"), "宽松解析失败返回 null");
        assertNull(SemVer.tryParse("5.4.0-"), "预发布段为空");
    }
}