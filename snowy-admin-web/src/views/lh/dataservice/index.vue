<template>
	<a-card :bordered="false">
		<a-space style="margin-bottom:12px">
			<a-button type="primary" @click="formRef.onOpen()">新增绑定</a-button>
			<a-button @click="openEmbed">SQLREST/Superset</a-button>
		</a-space>
		<s-table ref="tableRef" :columns="columns" :data="loadData" bordered :row-key="(r)=>r.id">
			<template #bodyCell="{ column, record }">
				<template v-if="column.dataIndex==='action'">
					<a-space>
						<a @click="formRef.onOpen(record)">编辑</a>
						<a @click="onTrial(record)">试跑</a>
						<a @click="onPublish(record)">发布</a>
						<a @click="onRetire(record)">下线</a>
					</a-space>
				</template>
			</template>
		</s-table>
		<Form ref="formRef" @successful="tableRef.refresh()" />
	</a-card>
</template>
<script setup>
	import { ref } from 'vue'
	import { message, Modal } from 'ant-design-vue'
	import dataapiApi from '@/api/lh/dataapiApi'
	import Form from './form.vue'
	const tableRef = ref(); const formRef = ref()
	const columns = [
		{ title: '名称', dataIndex: 'name' },
		{ title: '路径', dataIndex: 'publicPath' },
		{ title: '方法', dataIndex: 'method', width: 80 },
		{ title: '状态', dataIndex: 'state', width: 120 },
		{ title: '来源', dataIndex: 'sourceRef' },
		{ title: '操作', dataIndex: 'action', width: 220 }
	]
	const loadData = (p) => dataapiApi.page(p)
	const onTrial = (r) => dataapiApi.trial({ id: r.id }).then((res) => Modal.info({ title: '试跑结果', content: JSON.stringify(res) }))
	const onPublish = (r) => dataapiApi.publish({ id: r.id }).then(() => { message.success('已发布'); tableRef.value.refresh() })
	const onRetire = (r) => dataapiApi.retire({ id: r.id }).then(() => { message.success('已下线'); tableRef.value.refresh() })
	const openEmbed = () => dataapiApi.embedUrl().then((r) => Modal.info({ title: '外链', content: `SQLREST: ${r.sqlrest}\nSuperset: ${r.superset}` }))
</script>
