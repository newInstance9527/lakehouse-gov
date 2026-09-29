# 备份 / 恢复演练勾选

日期：____-__-__  
执行人：________  
环境：dev / test / prod（圈选）

## 备份

- [ ] `cp .env.example .env` 已填口令（未提交 Git）
- [ ] `./backup-core.sh` 退出 0
- [ ] `out/<stamp>/MANIFEST.txt` 存在
- [ ] `mysql-lakehouse_gov-*.sql.gz` 大小合理（>0）

## 恢复冒烟

- [ ] `./restore-mysql-smoke.sh out/<stamp>` 退出 0
- [ ] `SELECT 1` 成功
- [ ] 临时库已按需 DROP

## 记录

| 项 | 值 |
|----|-----|
| 备份耗时 | ____ min |
| 恢复耗时 | ____ min |
| 备注 | |

归档：复制本页到 `drills/YYYY-MM-DD.md`（目录已 gitignore 内容）。
