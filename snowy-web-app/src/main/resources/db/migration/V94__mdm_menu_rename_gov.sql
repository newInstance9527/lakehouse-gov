-- 门户菜单文案：主数据 MDM → 主数据治理（与 nav.js / 产品 IA 对齐）
UPDATE SYS_RESOURCE
SET NAME = '主数据治理',
    UPDATE_TIME = NOW()
WHERE ID = '1908901111999770636'
  AND CODE = 'mdm'
  AND DELETE_FLAG = 'NOT_DELETE';
