<template>
	<a-row :gutter="12">
		<a-col :span="6">
			<a-card size="small" class="q-cat-card" :title="null">
				<template #title>
					<div class="q-cat-title">
						<span>目录</span>
						<span class="q-cat-count">{{ tableCount }} 表</span>
					</div>
				</template>
				<a-input
					v-model:value="keyword"
					allow-clear
					size="small"
					placeholder="搜索库、表"
					style="margin-bottom: 8px"
				/>
				<a-tree
					v-if="filteredTree.length"
					v-model:expandedKeys="expandedKeys"
					:tree-data="filteredTree"
					:field-names="{ title: 'name', key: 'id', children: 'children' }"
					block-node
					@select="onSelectTree"
				>
					<template #title="node">
						<span class="q-node" :class="{ locked: node.locked }">
							<span v-if="node.layer" class="q-layer" :class="'q-layer-' + String(node.layer).toLowerCase()">{{ node.layer }}</span>
							<span class="q-name">{{ node.name }}</span>
							<span v-if="node.engine" class="q-engine">{{ node.engine }}</span>
							<span v-if="node.locked" class="q-lock">未授权</span>
							<span v-else-if="node.type === 'column'" class="q-type">{{ node.colType }}</span>
							<span v-if="node.masked" class="q-flag">脱敏</span>
							<span v-if="node.partition" class="q-flag part">分区</span>
						</span>
					</template>
				</a-tree>
				<div v-else class="q-empty">{{ keyword.trim() ? '没有匹配的库表' : '当前没有已登记的表' }}</div>
			</a-card>
		</a-col>
		<a-col :span="18">
			<a-card title="即席查询(Trino) · 默认扫描 ≤10GB" size="small">
				<a-textarea v-model:value="sql" :rows="8" />
				<a-space style="margin-top:8px">
					<a-button type="primary" :loading="running" @click="onExec">执行</a-button>
					<a-button :disabled="!running && !lastQueryId" @click="onCancel">取消</a-button>
					<a-button @click="loadHistory">历史</a-button>
					<a-button @click="onExport" :disabled="!rows.length">导出CSV</a-button>
				</a-space>
				<div v-if="metaText" style="margin-top:8px;font-size:12px;color:#666">{{ metaText }}</div>
				<a-table
					style="margin-top:12px"
					size="small"
					:columns="columns"
					:data-source="rows"
					:scroll="{ x: true }"
					:pagination="{ pageSize: 20 }"
					row-key="__i"
				/>
				<a-divider />
				<a-table
					size="small"
					:columns="histCols"
					:data-source="hist"
					:pagination="{ pageSize: 10 }"
					row-key="queryId"
					:custom-row="(record) => ({ onClick: () => onHistClick(record) })"
				/>
			</a-card>
		</a-col>
	</a-row>
</template>
<script setup>
	import { ref, onMounted, computed } from 'vue'
	import { message } from 'ant-design-vue'
	import queryApi from '@/api/lh/queryApi'

	const sql = ref('SHOW CATALOGS')
	const tree = ref([])
	const keyword = ref('')
	const expandedKeys = ref([])
	const rawCols = ref([])
	const rows = ref([])
	const hist = ref([])
	const running = ref(false)
	const lastQueryId = ref('')
	const lastTrinoId = ref('')
	const metaText = ref('')

	const columns = computed(() => rawCols.value.map((c) => ({ title: c, dataIndex: c })))

	function nodeHit(node, q) {
		const blob = `${node?.name || ''} ${node?.fqn || ''} ${node?.engine || ''} ${node?.hint || ''}`.toLowerCase()
		if (blob.includes(q)) return true
		return (node?.children || []).some((c) => nodeHit(c, q))
	}

	const filteredTree = computed(() => {
		const q = keyword.value.trim().toLowerCase()
		if (!q) return tree.value
		return (tree.value || []).filter((n) => nodeHit(n, q))
	})

	function countTables(nodes) {
		let n = 0
		for (const node of nodes || []) {
			if (node.type === 'table') n += 1
			else if (node.type !== 'column') n += countTables(node.children)
		}
		return n
	}

	const tableCount = computed(() => countTables(filteredTree.value))

	function findNode(nodes, id) {
		for (const n of nodes || []) {
			if (n.id === id) return n
			const hit = findNode(n.children, id)
			if (hit) return hit
		}
		return null
	}
	const histCols = [
		{ title: '时间', dataIndex: 'time', width: 100 },
		{ title: '摘要', dataIndex: 'summary', ellipsis: true },
		{ title: '耗时', dataIndex: 'duration', width: 80 },
		{ title: 'Scan', dataIndex: 'scan', width: 90 },
		{ title: '状态', dataIndex: 'statusLabel', width: 140 },
	]

	function mapRows(list) {
		return (list || []).map((r, i) => ({ ...r, __i: i }))
	}

	const onExec = async () => {
		running.value = true
		metaText.value = '执行中…'
		try {
			const r = await queryApi.exec({ sql: sql.value, ws: 'default', maxRows: 1000 })
			rawCols.value = r.columns || []
			rows.value = mapRows(r.rows)
			lastQueryId.value = r.queryId || ''
			lastTrinoId.value = r.trinoQueryId || ''
			const lim = r.scanLimit || '10GB'
			metaText.value = `queryId=${r.queryId || '—'} · ${r.statusLabel || r.status} · Scan ${r.scan || '—'} · 限额 ${lim}`
			if (r.scanOverLimit || r.blocked) {
				message.warning(r.message || '扫描超限额或被治理拦截')
			}
			await loadHistory()
		} catch (e) {
			message.error(e?.msg || e?.message || '执行失败')
			metaText.value = e?.msg || e?.message || '失败'
		} finally {
			running.value = false
		}
	}

	const onCancel = async () => {
		try {
			await queryApi.cancel({ queryId: lastQueryId.value, trinoQueryId: lastTrinoId.value })
			message.info('已取消')
			running.value = false
			await loadHistory()
		} catch (e) {
			message.error(e?.msg || e?.message || '取消失败')
		}
	}

	const loadHistory = async () => {
		const r = await queryApi.history({ limit: 30, mineOnly: true })
		hist.value = r || []
	}

	const onHistClick = (record) => {
		if (record?.sql) {
			sql.value = record.sql
			message.success('已回填历史 SQL')
		}
	}

	const onSelectTree = async (_keys, info) => {
		const n = info?.node?.data || info?.node?.dataRef || info?.node
		if (!n) return
		if (n.type === 'column' && n.columnName) {
			const cur = sql.value || ''
			sql.value = cur && !cur.endsWith(' ') ? `${cur} ${n.columnName}` : `${cur}${n.columnName}`
			return
		}
		if (n.locked) {
			message.warning('未授权，请走申请中心')
			return
		}
		if (n.type === 'table' && n.runnable === false) {
			message.warning('未挂接查询引擎')
			return
		}
		if (n.sampleSql) {
			sql.value = n.sampleSql
		}
		if (n.type === 'table' && !n.locked && n.runnable !== false && !n.columnsLoaded && (n.assetId || n.fqn)) {
			try {
				const r = await queryApi.columns({ assetId: n.assetId, fqn: n.fqn })
				const cols = (r?.columns || []).map((c) => ({
					id: `${n.id}.${c.name}`,
					name: c.name,
					type: 'column',
					columnName: c.name,
					colType: c.type,
					masked: !!c.masked,
					partition: !!c.partition,
					isLeaf: true,
				}))
				const target = findNode(tree.value, n.id)
				if (target) {
					target.children = cols
					target.columnsLoaded = true
					tree.value = tree.value.slice()
					if (!expandedKeys.value.includes(target.id)) {
						expandedKeys.value = [...expandedKeys.value, target.id]
					}
				}
			} catch (e) {
				message.warning(e?.msg || '列信息暂不可用')
			}
		}
	}

	const onExport = () => {
		if (!rows.value.length || !rawCols.value.length) return
		const esc = (v) => {
			const s = v == null ? '' : String(v)
			return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
		}
		const header = rawCols.value.map(esc).join(',')
		const lines = rows.value.map((r) => rawCols.value.map((c) => esc(r[c])).join(','))
		const blob = new Blob([[header, ...lines].join('\n')], { type: 'text/csv;charset=utf-8' })
		const a = document.createElement('a')
		a.href = URL.createObjectURL(blob)
		a.download = `query_result_masked_${lastQueryId.value || Date.now()}.csv`
		a.click()
		queryApi.export({ queryId: lastQueryId.value, rowCount: rows.value.length }).catch(() => {})
		message.success('已导出脱敏 CSV')
	}

	onMounted(() => {
		queryApi.schemaTree().then((r) => {
			tree.value = r || []
			expandedKeys.value = (tree.value || []).filter((n) => n.open).map((n) => n.id)
		})
		loadHistory()
	})
</script>
<style scoped>
.q-cat-title {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 8px;
}
.q-cat-count {
	font-size: 12px;
	font-weight: 400;
	color: rgba(0, 0, 0, 0.45);
}
.q-node {
	display: inline-flex;
	align-items: center;
	gap: 6px;
	min-width: 0;
	max-width: 100%;
}
.q-node.locked { color: rgba(0, 0, 0, 0.35); }
.q-name {
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}
.q-layer, .q-engine, .q-lock, .q-flag, .q-type {
	font-size: 10px;
	line-height: 16px;
	padding: 0 5px;
	border-radius: 4px;
	flex-shrink: 0;
}
.q-layer-ods { color: #ad6800; background: #fff7e6; }
.q-layer-dwd { color: #0958d9; background: #e6f4ff; }
.q-layer-dws { color: #08979c; background: #e6fffb; }
.q-layer-ads { color: #389e0d; background: #f6ffed; }
.q-layer-dim { color: #531dab; background: #f9f0ff; }
.q-engine { color: rgba(0, 0, 0, 0.45); background: #f5f5f5; }
.q-lock { color: #ad6800; background: #fff7e6; }
.q-type { color: rgba(0, 0, 0, 0.45); background: transparent; padding: 0; font-family: ui-monospace, Menlo, Consolas, monospace; }
.q-flag { color: #d46b08; background: #fff7e6; }
.q-flag.part { color: #0958d9; background: #e6f4ff; }
.q-empty {
	padding: 24px 8px;
	text-align: center;
	color: rgba(0, 0, 0, 0.45);
	font-size: 12px;
}
</style>
