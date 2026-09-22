<template>
	<a-card :bordered="false">
		<a-space wrap style="margin-bottom: 12px">
			<a-input v-model:value="ws" placeholder="ws" style="width: 140px" allow-clear />
			<a-button type="primary" :loading="loading" @click="refresh">刷新</a-button>
			<a-button :loading="busy" @click="onRunNow">立即执行日作业</a-button>
			<a-button :loading="busy" @click="onOrphanScan">孤儿扫描(dry-run)</a-button>
		</a-space>

		<a-space wrap style="margin-bottom: 16px">
			<a-statistic title="总存储(TB)" :value="overview.totalStorageTb ?? 0" :precision="2" style="margin-right: 28px" />
			<a-statistic title="月清理(GB)" :value="overview.monthCleanedGb ?? 0" style="margin-right: 28px" />
			<a-statistic title="合并成功" :value="overview.compactSuccessCount ?? 0" style="margin-right: 28px" />
			<a-statistic title="归档候选" :value="overview.archiveCandidatePartitions ?? 0" style="margin-right: 28px" />
			<a-statistic title="告警表" :value="overview.warnTableCount ?? 0" />
		</a-space>

		<a-alert
			v-if="jobsLatest.runId"
			type="info"
			show-icon
			style="margin-bottom: 12px"
			:message="`日作业 batch=${jobsLatest.batchId || '—'} · runId=${jobsLatest.runId} · ${jobsLatest.status || '—'}`"
			:description="jobStepsText"
		/>

		<a-tabs>
			<a-tab-pane key="policy" tab="策略">
				<a-space style="margin-bottom: 8px">
					<a-button type="primary" @click="openPolicy()">新建策略</a-button>
				</a-space>
				<a-table
					size="small"
					:columns="policyCols"
					:data-source="policies"
					:pagination="{ pageSize: 10 }"
					row-key="id"
					:loading="loading"
				>
					<template #bodyCell="{ column, record }">
						<template v-if="column.dataIndex === 'action'">
							<a-space>
								<a @click="openPolicy(record)">编辑</a>
								<a @click="onCompact(record)">合并</a>
								<a @click="onExpire(record)">过期</a>
							</a-space>
						</template>
					</template>
				</a-table>
			</a-tab-pane>
			<a-tab-pane key="top" tab="表存储 Top">
				<a-table
					size="small"
					:columns="topCols"
					:data-source="topStorage"
					:pagination="{ pageSize: 10 }"
					:row-key="(r) => r.fqtn || r.tableFqn || r.table"
					:loading="loading"
				>
					<template #bodyCell="{ column, record }">
						<template v-if="column.dataIndex === 'action'">
							<a-space>
								<a @click="onCompact({ tableFqn: record.fqtn || record.tableFqn })">合并</a>
								<a @click="onExpire({ tableFqn: record.fqtn || record.tableFqn })">过期</a>
							</a-space>
						</template>
					</template>
				</a-table>
			</a-tab-pane>
			<a-tab-pane key="runs" tab="运行留痕">
				<a-table
					size="small"
					:columns="runCols"
					:data-source="runs"
					:pagination="{ pageSize: 10 }"
					row-key="runId"
					:loading="loading"
				>
					<template #bodyCell="{ column, record }">
						<template v-if="column.dataIndex === 'action'">
							<a v-if="record.runId" @click="onSync(record.runId)">同步 DS</a>
						</template>
					</template>
				</a-table>
			</a-tab-pane>
			<a-tab-pane key="orphan" tab="孤儿扫描结果">
				<a-table
					size="small"
					:columns="orphanCols"
					:data-source="orphanRows"
					:pagination="false"
					:row-key="(r, i) => r.scanId || r.bucket || i"
				/>
			</a-tab-pane>
		</a-tabs>

		<a-modal
			v-model:open="policyOpen"
			:title="policyForm.id ? '编辑策略' : '新建策略'"
			ok-text="保存"
			:confirm-loading="busy"
			@ok="savePolicy"
		>
			<a-form layout="vertical">
				<a-form-item label="表 FQN" required>
					<a-input v-model:value="policyForm.tableFqn" :disabled="!!policyForm.id" placeholder="iceberg.ods.xxx" />
				</a-form-item>
				<a-form-item label="状态">
					<a-select v-model:value="policyForm.status" :options="[{ label: 'active', value: 'active' }, { label: 'disabled', value: 'disabled' }]" />
				</a-form-item>
				<a-form-item label="保留天数 / 快照数 / 最少快照">
					<a-space>
						<a-input-number v-model:value="policyForm.keepDays" :min="1" placeholder="days" />
						<a-input-number v-model:value="policyForm.keepCount" :min="1" placeholder="count" />
						<a-input-number v-model:value="policyForm.minSnapshots" :min="1" placeholder="min" />
					</a-space>
				</a-form-item>
				<a-form-item label="合并级别 / 目标文件 MB">
					<a-space>
						<a-select
							v-model:value="policyForm.compactLevel"
							style="width: 100px"
							:options="['L1', 'L2', 'L3'].map((i) => ({ label: i, value: i }))"
						/>
						<a-input-number v-model:value="policyForm.targetFileMb" :min="1" />
					</a-space>
				</a-form-item>
				<a-form-item label="孤儿窗(天) / 安全窗(小时) / 分区过期(天)">
					<a-space>
						<a-input-number v-model:value="policyForm.orphanOlderDays" :min="7" />
						<a-input-number v-model:value="policyForm.orphanSafetyHours" :min="0" />
						<a-input-number v-model:value="policyForm.partitionExpireDays" :min="0" />
					</a-space>
				</a-form-item>
				<a-form-item label="分层 / Owner">
					<a-space>
						<a-input v-model:value="policyForm.layer" placeholder="ODS" style="width: 100px" />
						<a-input v-model:value="policyForm.owner" placeholder="owner" />
					</a-space>
				</a-form-item>
				<a-form-item label="备注">
					<a-textarea v-model:value="policyForm.remark" :rows="2" />
				</a-form-item>
			</a-form>
		</a-modal>
	</a-card>
</template>

<script setup>
	import { computed, onMounted, reactive, ref } from 'vue'
	import { message } from 'ant-design-vue'
	import lifecycleApi from '@/api/lh/lifecycleApi'

	const ws = ref('default')
	const loading = ref(false)
	const busy = ref(false)
	const overview = ref({})
	const jobsLatest = ref({})
	const policies = ref([])
	const topStorage = ref([])
	const runs = ref([])
	const orphanRows = ref([])
	const policyOpen = ref(false)
	const policyForm = reactive({
		id: undefined,
		tableFqn: '',
		ws: 'default',
		status: 'active',
		keepDays: 7,
		keepCount: 10,
		minSnapshots: 1,
		compactLevel: 'L2',
		targetFileMb: 128,
		orphanOlderDays: 7,
		orphanSafetyHours: 72,
		partitionExpireDays: 0,
		layer: '',
		owner: '',
		remark: ''
	})

	const policyCols = [
		{ title: '表 FQN', dataIndex: 'tableFqn', ellipsis: true },
		{ title: '状态', dataIndex: 'status', width: 90 },
		{ title: '保留天', dataIndex: 'keepDays', width: 80 },
		{ title: '级别', dataIndex: 'compactLevel', width: 70 },
		{ title: '孤儿天', dataIndex: 'orphanOlderDays', width: 80 },
		{ title: '分层', dataIndex: 'layer', width: 70 },
		{ title: '操作', dataIndex: 'action', width: 180 }
	]
	const topCols = [
		{ title: '表', dataIndex: 'fqtn', ellipsis: true, customRender: ({ record }) => record.fqtn || record.tableFqn || record.table },
		{ title: '物理量', dataIndex: 'totalBytes', width: 110 },
		{ title: '可回收', dataIndex: 'reclaimableBytes', width: 110 },
		{ title: '文件数', dataIndex: 'fileCount', width: 90 },
		{ title: '操作', dataIndex: 'action', width: 140 }
	]
	const runCols = [
		{ title: 'runId', dataIndex: 'runId', width: 160, ellipsis: true },
		{ title: '类型', dataIndex: 'kind', width: 90 },
		{ title: '表', dataIndex: 'tableFqn', ellipsis: true },
		{ title: '状态', dataIndex: 'status', width: 90 },
		{ title: 'DS', dataIndex: 'dsTaskId', width: 120, ellipsis: true },
		{ title: '操作', dataIndex: 'action', width: 90 }
	]
	const orphanCols = [
		{ title: '桶', dataIndex: 'bucket', width: 140 },
		{ title: '候选数', dataIndex: 'candidateCount', width: 100 },
		{ title: '状态', dataIndex: 'status', ellipsis: true },
		{ title: '结果', dataIndex: 'resultUri', ellipsis: true }
	]

	const jobStepsText = computed(() => {
		const steps = jobsLatest.value?.steps || []
		if (!steps.length) return '暂无步骤投影'
		return steps.map((s) => `${s.step}.${s.name || s.stepName}:${s.status}`).join(' · ')
	})

	const refresh = async () => {
		loading.value = true
		try {
			const params = { ws: ws.value || 'default' }
			const [ov, jobs, pol, top, runPage] = await Promise.all([
				lifecycleApi.overview(params),
				lifecycleApi.jobsLatest(params),
				lifecycleApi.policies(params),
				lifecycleApi.topStorage({ ...params, limit: 20 }),
				lifecycleApi.runs({ ...params, current: 1, size: 20 })
			])
			overview.value = ov || {}
			jobsLatest.value = jobs || {}
			policies.value = Array.isArray(pol) ? pol : pol?.records || pol?.list || []
			topStorage.value = Array.isArray(top) ? top : top?.list || top?.records || []
			runs.value = runPage?.records || runPage?.list || (Array.isArray(runPage) ? runPage : [])
		} catch (e) {
			message.error(e?.message || '加载失败')
		} finally {
			loading.value = false
		}
	}

	const openPolicy = (row) => {
		Object.assign(policyForm, {
			id: undefined,
			tableFqn: '',
			ws: ws.value || 'default',
			status: 'active',
			keepDays: 7,
			keepCount: 10,
			minSnapshots: 1,
			compactLevel: 'L2',
			targetFileMb: 128,
			orphanOlderDays: 7,
			orphanSafetyHours: 72,
			partitionExpireDays: 0,
			layer: '',
			owner: '',
			remark: ''
		})
		if (row) Object.assign(policyForm, row)
		policyOpen.value = true
	}

	const savePolicy = async () => {
		if (!policyForm.tableFqn?.trim()) {
			message.warning('表 FQN 必填')
			return
		}
		busy.value = true
		try {
			const payload = { ...policyForm, ws: ws.value || policyForm.ws || 'default' }
			delete payload.id
			const r = await lifecycleApi.upsertPolicy(payload)
			message.success(`已保存 ${r?.tableFqn || payload.tableFqn}`)
			policyOpen.value = false
			await refresh()
		} catch (e) {
			message.error(e?.message || '保存失败')
		} finally {
			busy.value = false
		}
	}

	const toastRun = (r, action) => {
		const id = r?.runId || r?.id || '—'
		message.success(`${action} 已提交 · runId=${id}`)
	}

	const onCompact = async (row) => {
		const tableFqn = row?.tableFqn
		if (!tableFqn) return
		busy.value = true
		try {
			const r = await lifecycleApi.compact({ tableFqn, ws: ws.value || 'default' })
			toastRun(r, '合并')
			await refresh()
		} catch (e) {
			message.error(e?.message || '合并失败')
		} finally {
			busy.value = false
		}
	}

	const onExpire = async (row) => {
		const tableFqn = row?.tableFqn
		if (!tableFqn) return
		busy.value = true
		try {
			const r = await lifecycleApi.expire({ tableFqn, ws: ws.value || 'default' })
			toastRun(r, '过期')
			await refresh()
		} catch (e) {
			message.error(e?.message || '过期失败')
		} finally {
			busy.value = false
		}
	}

	const onRunNow = async () => {
		busy.value = true
		try {
			const r = await lifecycleApi.runNow({ ws: ws.value || 'default' })
			toastRun(r, '日作业')
			await refresh()
		} catch (e) {
			message.error(e?.message || '日作业提交失败')
		} finally {
			busy.value = false
		}
	}

	const onOrphanScan = async () => {
		busy.value = true
		try {
			const r = await lifecycleApi.orphanScan({ ws: ws.value || 'default', dryRun: true })
			const list = r?.buckets || r?.candidates || r?.rows || r?.list || (Array.isArray(r) ? r : [])
			orphanRows.value = list.length
				? list
				: [{ bucket: '—', candidateCount: r?.candidateCount ?? 0, status: r?.message || '无结果', resultUri: '' }]
			toastRun(r, '孤儿扫描')
			await refresh()
		} catch (e) {
			message.error(e?.message || '孤儿扫描失败')
		} finally {
			busy.value = false
		}
	}

	const onSync = async (runId) => {
		busy.value = true
		try {
			await lifecycleApi.syncRun(runId)
			message.success(`已同步 ${runId}`)
			await refresh()
		} catch (e) {
			message.error(e?.message || '同步失败')
		} finally {
			busy.value = false
		}
	}

	onMounted(refresh)
</script>
