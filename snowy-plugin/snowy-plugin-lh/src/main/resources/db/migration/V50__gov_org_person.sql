-- 部门非系统人员轻量档案（挂 SYS_ORG；无登录账号）
-- 规格：doc/部门管理.md

CREATE TABLE IF NOT EXISTS `gov_org_person` (
  `id`            varchar(20)   NOT NULL COMMENT '主键',
  `revision`      int           NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `status`        varchar(32)   DEFAULT 'active' COMMENT 'active/disabled',
  `org_id`        varchar(20)   NOT NULL COMMENT 'SYS_ORG.id 主部门',
  `name`          varchar(128)  NOT NULL COMMENT '姓名',
  `phone`         varchar(64)   DEFAULT NULL COMMENT '手机',
  `email`         varchar(128)  DEFAULT NULL COMMENT '邮箱',
  `job_title`     varchar(128)  DEFAULT NULL COMMENT '职务称谓（非 SYS_POSITION）',
  `remark`        varchar(512)  DEFAULT NULL COMMENT '备注',
  `delete_flag`   varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`   datetime      DEFAULT NULL,
  `create_user`   varchar(20)   DEFAULT NULL,
  `update_time`   datetime      DEFAULT NULL,
  `update_user`   varchar(20)   DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_gov_org_person_org` (`org_id`) USING BTREE,
  KEY `idx_gov_org_person_name` (`name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='部门非系统人员（无登录）';

-- 门户菜单：部门管理（name/path 与 nav.js sys-org 对齐）
INSERT INTO `SYS_RESOURCE` (`ID`,`PARENT_ID`,`TITLE`,`NAME`,`CODE`,`CATEGORY`,`MODULE`,`MENU_TYPE`,`PATH`,`COMPONENT`,`ICON`,`COLOR`,`VISIBLE`,`DISPLAY_LAYOUT`,`KEEP_LIVE`,`SORT_CODE`,`EXT_JSON`,`DELETE_FLAG`,`CREATE_TIME`,`CREATE_USER`,`UPDATE_TIME`,`UPDATE_USER`)
SELECT '1908901111999770635', '0', '部门管理', 'sys-org', 'sys-org', 'MENU', '1908901111999770501', 'MENU', '/sys/org', NULL, 'cluster-outlined', NULL, 'YES', 'YES', 'YES', 35, NULL, 'NOT_DELETE', NOW(), NULL, NULL, NULL
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `SYS_RESOURCE` WHERE `ID` = '1908901111999770635');

INSERT INTO `SYS_RELATION` (`ID`,`OBJECT_ID`,`TARGET_ID`,`CATEGORY`,`EXT_JSON`)
SELECT '1908902111999770035', '1570687866138206208', '1908901111999770635', 'SYS_ROLE_HAS_RESOURCE',
       '{"menuId":"1908901111999770635","buttonInfo":[]}'
FROM DUAL
WHERE EXISTS (SELECT 1 FROM `SYS_RESOURCE` WHERE `ID` = '1908901111999770635')
  AND NOT EXISTS (
    SELECT 1 FROM `SYS_RELATION` r
    WHERE r.`OBJECT_ID` = '1570687866138206208' AND r.`TARGET_ID` = '1908901111999770635' AND r.`CATEGORY` = 'SYS_ROLE_HAS_RESOURCE'
  );
