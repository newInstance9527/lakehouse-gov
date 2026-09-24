-- 业界数据治理角色种子（SYS_ROLE · GLOBAL · 幂等）
-- 对齐 DAMA DMBOK / 平台 §28.1：认责与审批岗 ≠ 引擎 ACL
-- 已存在（V51）：dataOwner / dataSteward / dataAnalyst；本脚本补齐其余并回填描述

-- ========== 回填 V51 三角色的职责说明 ==========
UPDATE `SYS_ROLE` SET `EXT_JSON` = '{"suite":"lh-gov","domain":"gov","desc":"资产/指标认责人；权限与出湖申请默认审批候选人"}'
WHERE `CODE` = 'dataOwner' AND (`EXT_JSON` IS NULL OR `EXT_JSON` = '');

UPDATE `SYS_ROLE` SET `EXT_JSON` = '{"suite":"lh-gov","domain":"gov","desc":"数据管家：标准/术语/质量规则落地与日常治理跟进"}'
WHERE `CODE` = 'dataSteward' AND (`EXT_JSON` IS NULL OR `EXT_JSON` = '');

UPDATE `SYS_ROLE` SET `EXT_JSON` = '{"suite":"lh-gov","domain":"gov","desc":"业务分析消费方；以申请读 ADS/指标为主"}'
WHERE `CODE` = 'dataAnalyst' AND (`EXT_JSON` IS NULL OR `EXT_JSON` = '');

-- ========== 补齐治理角色 ==========
INSERT INTO `SYS_ROLE` (`ID`,`ORG_ID`,`NAME`,`CODE`,`CATEGORY`,`SORT_CODE`,`EXT_JSON`,`DELETE_FLAG`,`CREATE_TIME`)
SELECT '1908904111999773004', NULL, '数据治理官', 'dataGovOfficer', 'GLOBAL', 9,
       '{"suite":"lh-gov","domain":"gov","desc":"数据治理委员会执行官：政策、标准优先级、跨域争议仲裁"}',
       'NOT_DELETE', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `SYS_ROLE` WHERE `ID` = '1908904111999773004' OR `CODE` = 'dataGovOfficer');

INSERT INTO `SYS_ROLE` (`ID`,`ORG_ID`,`NAME`,`CODE`,`CATEGORY`,`SORT_CODE`,`EXT_JSON`,`DELETE_FLAG`,`CREATE_TIME`)
SELECT '1908904111999773005', NULL, '安全岗', 'securityOfficer', 'GLOBAL', 13,
       '{"suite":"lh-gov","domain":"security","desc":"安全与脱敏：高敏感明文/出湖加签、审计与策略复核"}',
       'NOT_DELETE', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `SYS_ROLE` WHERE `ID` = '1908904111999773005' OR `CODE` = 'securityOfficer');

INSERT INTO `SYS_ROLE` (`ID`,`ORG_ID`,`NAME`,`CODE`,`CATEGORY`,`SORT_CODE`,`EXT_JSON`,`DELETE_FLAG`,`CREATE_TIME`)
SELECT '1908904111999773006', NULL, '合规专员', 'complianceOfficer', 'GLOBAL', 14,
       '{"suite":"lh-gov","domain":"security","desc":"合规/被遗忘权：合规删除审批链、留存与证据复核"}',
       'NOT_DELETE', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `SYS_ROLE` WHERE `ID` = '1908904111999773006' OR `CODE` = 'complianceOfficer');

INSERT INTO `SYS_ROLE` (`ID`,`ORG_ID`,`NAME`,`CODE`,`CATEGORY`,`SORT_CODE`,`EXT_JSON`,`DELETE_FLAG`,`CREATE_TIME`)
SELECT '1908904111999773007', NULL, '质量工程师', 'qualityEngineer', 'GLOBAL', 15,
       '{"suite":"lh-gov","domain":"quality","desc":"数据质量：规则设计、门禁配置、失败修复跟进"}',
       'NOT_DELETE', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `SYS_ROLE` WHERE `ID` = '1908904111999773007' OR `CODE` = 'qualityEngineer');

INSERT INTO `SYS_ROLE` (`ID`,`ORG_ID`,`NAME`,`CODE`,`CATEGORY`,`SORT_CODE`,`EXT_JSON`,`DELETE_FLAG`,`CREATE_TIME`)
SELECT '1908904111999773008', NULL, '数据工程师', 'dataEngineer', 'GLOBAL', 16,
       '{"suite":"lh-gov","domain":"engineering","desc":"入湖/加工开发：ETL、脚本开发与发布；引擎写权限走作业 SA/申请"}',
       'NOT_DELETE', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `SYS_ROLE` WHERE `ID` = '1908904111999773008' OR `CODE` = 'dataEngineer');

INSERT INTO `SYS_ROLE` (`ID`,`ORG_ID`,`NAME`,`CODE`,`CATEGORY`,`SORT_CODE`,`EXT_JSON`,`DELETE_FLAG`,`CREATE_TIME`)
SELECT '1908904111999773009', NULL, '数据运维', 'dataOps', 'GLOBAL', 17,
       '{"suite":"lh-gov","domain":"ops","desc":"平台与作业运维：调度、发布门禁执行、故障值班"}',
       'NOT_DELETE', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `SYS_ROLE` WHERE `ID` = '1908904111999773009' OR `CODE` = 'dataOps');

INSERT INTO `SYS_ROLE` (`ID`,`ORG_ID`,`NAME`,`CODE`,`CATEGORY`,`SORT_CODE`,`EXT_JSON`,`DELETE_FLAG`,`CREATE_TIME`)
SELECT '1908904111999773010', NULL, '指标负责人', 'metricOwner', 'GLOBAL', 18,
       '{"suite":"lh-gov","domain":"metric","desc":"指标口径认责：发布/变更审批与口径争议处理"}',
       'NOT_DELETE', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `SYS_ROLE` WHERE `ID` = '1908904111999773010' OR `CODE` = 'metricOwner');

INSERT INTO `SYS_ROLE` (`ID`,`ORG_ID`,`NAME`,`CODE`,`CATEGORY`,`SORT_CODE`,`EXT_JSON`,`DELETE_FLAG`,`CREATE_TIME`)
SELECT '1908904111999773011', NULL, '元数据管理员', 'metadataAdmin', 'GLOBAL', 19,
       '{"suite":"lh-gov","domain":"metadata","desc":"目录与元数据：资产登记规范、术语/标准维护、血缘治理"}',
       'NOT_DELETE', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `SYS_ROLE` WHERE `ID` = '1908904111999773011' OR `CODE` = 'metadataAdmin');

INSERT INTO `SYS_ROLE` (`ID`,`ORG_ID`,`NAME`,`CODE`,`CATEGORY`,`SORT_CODE`,`EXT_JSON`,`DELETE_FLAG`,`CREATE_TIME`)
SELECT '1908904111999773012', NULL, 'API 服务管理员', 'apiAdmin', 'GLOBAL', 20,
       '{"suite":"lh-gov","domain":"api","desc":"数据服务：API 发布审批、订阅配额与网关策略协同"}',
       'NOT_DELETE', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `SYS_ROLE` WHERE `ID` = '1908904111999773012' OR `CODE` = 'apiAdmin');

INSERT INTO `SYS_ROLE` (`ID`,`ORG_ID`,`NAME`,`CODE`,`CATEGORY`,`SORT_CODE`,`EXT_JSON`,`DELETE_FLAG`,`CREATE_TIME`)
SELECT '1908904111999773013', NULL, '业务域负责人', 'domainLead', 'GLOBAL', 21,
       '{"suite":"lh-gov","domain":"domain","desc":"业务域 Owner：域内资产优先级、跨团队申请加签"}',
       'NOT_DELETE', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `SYS_ROLE` WHERE `ID` = '1908904111999773013' OR `CODE` = 'domainLead');

-- 平台内置角色说明（不改 code，仅补描述）
UPDATE `SYS_ROLE` SET `EXT_JSON` = '{"suite":"snowy","domain":"platform","desc":"平台超级管理员：系统配置、全量菜单与授权"}'
WHERE `CODE` = 'superAdmin' AND (`EXT_JSON` IS NULL OR `EXT_JSON` = '');

UPDATE `SYS_ROLE` SET `EXT_JSON` = '{"suite":"snowy","domain":"platform","desc":"业务管理员：业务侧系统管理与默认门户菜单"}'
WHERE `CODE` = 'bizAdmin' AND (`EXT_JSON` IS NULL OR `EXT_JSON` = '');
