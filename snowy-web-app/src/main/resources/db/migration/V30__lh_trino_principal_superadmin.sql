-- 门户默认超管 ↔ 人类 Trino 主体（非表 ACL；禁止映射服务账号 admin）
-- Trino password / impersonation 须允许主体 superAdmin，否则执行仍会失败。

INSERT INTO `lh_trino_principal` (
  `id`, `portal_user_id`, `portal_account`, `trino_user`, `kind`, `status`,
  `remark`, `delete_flag`, `create_time`, `create_user`, `update_time`, `update_user`
)
SELECT
  '2100000000000000030',
  '1543837863788879871',
  'superAdmin',
  'superAdmin',
  'human',
  'active',
  'seed: portal superAdmin → Trino human principal',
  'NOT_DELETE',
  NOW(),
  '1543837863788879871',
  NOW(),
  '1543837863788879871'
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM `lh_trino_principal`
  WHERE `portal_user_id` = '1543837863788879871' AND `delete_flag` = 'NOT_DELETE'
)
AND NOT EXISTS (
  SELECT 1 FROM `lh_trino_principal`
  WHERE `trino_user` = 'superAdmin' AND `delete_flag` = 'NOT_DELETE'
);
