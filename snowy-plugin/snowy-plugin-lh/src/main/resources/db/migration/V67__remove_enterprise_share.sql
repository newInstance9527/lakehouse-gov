-- 废除企业共享层：撤回发布态、软删 enterprise 空间，偏好回落 default
-- 规格：doc/工作空间.md（2026-09-28 移除企业共享）

-- 已发布到共享层的资产回到本空间私有
UPDATE `gov_asset`
   SET `visibility` = 'private_ws',
       `share_status` = 'none',
       `update_time` = NOW()
 WHERE `delete_flag` = 'NOT_DELETE'
   AND (
     `visibility` IN ('shared_enterprise', 'listed_public')
     OR `share_status` IN ('published', 'pending')
   );

ALTER TABLE `gov_asset`
  MODIFY COLUMN `visibility` varchar(32) NOT NULL DEFAULT 'private_ws'
    COMMENT '门户可见性（遗留列；现仅 private_ws）',
  MODIFY COLUMN `share_status` varchar(32) NOT NULL DEFAULT 'none'
    COMMENT '遗留列（企业共享已废除，恒为 none）';

-- 软删企业共享容器空间与配额
UPDATE `gov_ws`
   SET `status` = 'archived',
       `delete_flag` = 'DELETED',
       `remark` = '企业共享层已废除',
       `update_time` = NOW()
 WHERE `ws_code` = 'enterprise'
   AND `delete_flag` = 'NOT_DELETE';

UPDATE `gov_ws_quota`
   SET `delete_flag` = 'DELETED',
       `update_time` = NOW()
 WHERE `ws_code` = 'enterprise'
   AND `delete_flag` = 'NOT_DELETE';

-- 用户偏好若指向 enterprise → 回落 default
UPDATE `gov_ws_user_pref`
   SET `current_ws_code` = 'default',
       `update_time` = NOW()
 WHERE `current_ws_code` = 'enterprise';
