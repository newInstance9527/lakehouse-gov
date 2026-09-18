<template>
	<a-card :bordered="false">
		<a-space wrap style="margin-bottom: 12px">
			<a-checkable-tag v-for="t in types" :key="t" :checked="searchFormState.type === t" @change="() => onType(t)">{{ t }}</a-checkable-tag>
			<a-input v-model:value="searchFormState.keyword" placeholder="关键字" style="width: 200px" allow-clear />
			<a-button type="primary" @click="tableRef.refresh(true)">查询</a-button>
			<a-button type="primary" @click="formRef.onOpen()">新增</a-button>
		</a-space>
		<s-table ref="tableRef" :columns="columns" :data="loadData" bordered :row-key="(r) => r.id">
			<template #bodyCell="{ column, record }">
				<template v-if="column.dataIndex === 'action'">
					<a-space>
						<a @click="formRef.onOpen(record)">编辑</a>
						<a @click="onTest(record)">测试</a>
						<a @click="onPreview(record)">Schema</a>
						<a-popconfirm title="确认删除？" @confirm="onDelete(record)"><a style="color:#ff4d4f">删除</a></a-popconfirm>
					</a-space>
				</template>
			</template>
		</s-table>
		<Form ref="formRef" @successful="tableRef.refresh()" />
		<a-drawer v-model:open="previewOpen" title="Schema预览" width="520">
			<a-table :columns="[{title:'表',dataIndex:'table'},{title:'列',dataIndex:'column'},{title:'类型',dataIndex:'type'}]" :data-source="previewRows" :pagination="false" size="small" />
		</a-drawer>
	</a-card>
</template>
<script setup>
	import { ref } from 'vue'
	import { message } from 'ant-design-vue'
	import datasourceApi from '@/api/lh/datasourceApi'
	import Form from './form.vue'

	const types = ['mysql', 'pg', 'kafka', 's3', 'file', 'http_api']
	const tableRef = ref()
	const formRef = ref()
	const searchFormState = ref({ type: undefined, keyword: undefined })
	const previewOpen = ref(false)
	const previewRows = ref([])
	const columns = [
		{ title: '名称', dataIndex: 'name' },
		{ title: '类型', dataIndex: 'type', width: 100 },
		{ title: '状态', dataIndex: 'status', width: 100 },
		{ title: '用途', dataIndex: 'purposes' },
		{ title: '脱敏连接', dataIndex: 'connMasked', ellipsis: true },
		{ title: '操作', dataIndex: 'action', width: 220 }
	]
	const onType = (t) => {
		searchFormState.value.type = searchFormState.value.type === t ? undefined : t
		tableRef.value.refresh(true)
	}
	const loadData = (parameter) => datasourceApi.page(Object.assign(parameter, searchFormState.value)).then((r) => r)
	const onDelete = (record) => datasourceApi.delete([{ id: record.id }]).then(() => { message.success('已删除'); tableRef.value.refresh() })
	const onTest = (record) => datasourceApi.test({ id: record.id, type: record.type }).then((r) => {
		message[r.ok ? 'success' : 'error'](r.ok ? `连通成功 ${r.costMs}ms` : r.error)
	})
	const onPreview = (record) => datasourceApi.previewSchema({ id: record.id }).then((r) => {
		previewRows.value = r.columns || []
		previewOpen.value = true
	})
</script>
