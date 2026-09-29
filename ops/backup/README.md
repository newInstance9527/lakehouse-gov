# ops/backup · 核心库备份

见仓库文档 [`doc/可靠底座-备份与恢复.md`](../../../doc/可靠底座-备份与恢复.md)。

```bash
cp .env.example .env   # 填 MYSQL_PASSWORD 等
chmod +x backup-core.sh restore-mysql-smoke.sh
./backup-core.sh
./restore-mysql-smoke.sh out/<stamp>
```

`out/` 与 `drills/*.md` 含敏感/演练记录，已建议 gitignore。
