<template>
	<a-row :gutter="12">
		<a-col :span="6">
			<a-card title="Schema" size="small"><a-tree :tree-data="tree" :field-names="{ title: 'title', key: 'key', children: 'children' }" /></a-card>
		</a-col>
		<a-col :span="18">
			<a-card title="即席查询(Trino)" size="small">
				<a-textarea v-model:value="sql" :rows="8" />
				<a-space style="margin-top:8px">
					<a-button type="primary" @click="onExec">执行</a-button>
					<a-button @click="loadHistory">历史</a-button>
				</a-space>
				<a-table style="margin-top:12px" size="small" :columns="columns" :data-source="rows" :scroll="{ x: true }" :pagination="{ pageSize: 20 }" />
			</a-card>
		</a-col>
	</a-row>
</template>
<script setup>
	import { ref, onMounted, computed } from 'vue'
	import queryApi from '@/api/lh/queryApi'
	const sql = ref('SHOW CATALOGS')
	const tree = ref([])
	const rawCols = ref([])
	const rows = ref([])
	const columns = computed(() => rawCols.value.map((c) => ({ title: c, dataIndex: c })))
	const onExec = () => queryApi.exec({ sql: sql.value, ws: 'default' }).then((r) => {
		rawCols.value = r.columns || []
		rows.value = r.rows || []
	})
	const loadHistory = () => queryApi.history({}).then((r) => { rows.value = r || []; rawCols.value = ['queryId','sqlTrunc','state','durMs'] })
	onMounted(() => queryApi.schemaTree().then((r) => { tree.value = r || [] }))
</script>
