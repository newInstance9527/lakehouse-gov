package vip.xiaonuo.lh.modular.sec.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LhOpsPrivilegeEnumTest {

    @Test
    void manageCoversEditAndDelete() {
        assertTrue(LhOpsPrivilegeEnum.satisfies("MANAGE", LhOpsPrivilegeEnum.EDIT));
        assertTrue(LhOpsPrivilegeEnum.satisfies("MANAGE", LhOpsPrivilegeEnum.DELETE));
        assertTrue(LhOpsPrivilegeEnum.satisfies("MANAGE", LhOpsPrivilegeEnum.MANAGE));
    }

    @Test
    void editAndDeleteDoNotCross() {
        assertTrue(LhOpsPrivilegeEnum.satisfies("EDIT", LhOpsPrivilegeEnum.EDIT));
        assertFalse(LhOpsPrivilegeEnum.satisfies("EDIT", LhOpsPrivilegeEnum.DELETE));
        assertFalse(LhOpsPrivilegeEnum.satisfies("DELETE", LhOpsPrivilegeEnum.EDIT));
        assertTrue(LhOpsPrivilegeEnum.satisfies("DELETE", LhOpsPrivilegeEnum.DELETE));
        assertFalse(LhOpsPrivilegeEnum.satisfies("EDIT", LhOpsPrivilegeEnum.MANAGE));
    }
}
