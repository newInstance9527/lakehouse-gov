-- J2/A8：申请多级审批（Owner → 安全加签）
-- 状态机扩展：pending → pending_security → approved（机密明文）；链路标记落 payload JSON，无新列。

ALTER TABLE `apply_ticket`
  MODIFY COLUMN `status` varchar(32) DEFAULT 'draft'
    COMMENT 'draft/pending/pending_security/approved/rejected/cancelled/expired';
