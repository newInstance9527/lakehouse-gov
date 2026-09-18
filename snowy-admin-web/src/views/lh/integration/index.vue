<template>
	<a-row :gutter="12">
		<a-col :span="6">
			<a-card title="DAG任务" size="small">
				<a-space style="margin-bottom:8px"><a-button type="primary" size="small" @click="onAdd">新建</a-button><a-button size="small" @click="loadDags">刷新</a-button></a-space>
				<a-list :data-source="dags" size="small">
					<template #renderItem="{ item }">
						<a-list-item @click="selectDag(item)" style="cursor:pointer" :class="{active: current?.id===item.id}">
							{{ item.name }} <a-tag>{{ item.status }}</a-tag>
						</a-list-item>
					</template>
				</a-list>
			</a-card>
		</a-col>
		<a-col :span="10">
			<a-card title="图定义(nodes/edges JSON)" size="small">
				<a-textarea v-model:value="nodesJson" :rows="10" placeholder="nodes" />
				<a-textarea v-model:value="edgesJson" :rows="6" style="margin-top:8px" placeholder="edges" />
				<a-space style="margin-top:8px">
					<a-button type="primary" :disabled="!current" @click="onSave">保存三写</a-button>
					<a-button :disabled="!current" @click="onValidate">校验</a-button>
					<a-button type="primary" danger :disabled="!current" @click="onDeploy">发布</a-button>
				</a-space>
			</a-card>
		</a-col>
		<a-col :span="8">
			<a-card title="结果" size="small">
				<pre style="white-space:pre-wrap;max-height:480px;overflow:auto">{{ resultText }}</pre>
			</a-card>
		</a-col>
	</a-row>
</template>
<script setup>
	import { ref, onMounted } from 'vue'
	import { message, Modal } from 'ant-design-vue'
	import dagApi from '@/api/lh/dagApi'

	const dags = ref([])
	const current = ref(null)
	const nodesJson = ref(JSON.stringify([{ id: 'n1', type: 'source', cfg: { dsId: '', mode: 'cdc' } }, { id: 'n2', type: 'sink_iceberg', cfg: { table: 'iceberg.ods_trade.s_order' } }], null, 2))
	const edgesJson = ref(JSON.stringify([{ source: 'n1', target: 'n2' }], null, 2))
	const resultText = ref('')

	const loadDags = () => dagApi.page({ current: 1, size: 100 }).then((r) => { dags.value = r.records || [] })
	const onAdd = () => {
		Modal.confirm({
			title: '新建DAG',
			content: '将创建 demo_ods_order_cdc',
			onOk: () => dagApi.submitForm({ name: 'demo_ods_order_cdc', cron: '0 0 * * * ? *' }).then(() => { message.success('已创建'); loadDags() })
		})
	}
	const selectDag = (item) => {
		current.value = item
		dagApi.graph({ id: item.id }).then((g) => {
			nodesJson.value = JSON.stringify(JSON.parse(g.nodes || '[]'), null, 2)
			edgesJson.value = JSON.stringify(JSON.parse(g.edges || '[]'), null, 2)
		})
	}
	const onSave = () => dagApi.saveGraph({ id: current.value.id, nodes: nodesJson.value, edges: edgesJson.value }).then((r) => { resultText.value = JSON.stringify(r, null, 2); message.success('已保存'); loadDags() })
	const onValidate = () => dagApi.validate({ id: current.value.id }).then((r) => { resultText.value = JSON.stringify(r, null, 2) })
	const onDeploy = () => dagApi.deploy({ id: current.value.id }).then((r) => { resultText.value = JSON.stringify(r, null, 2); message.success('已发布'); loadDags() })
	onMounted(loadDags)
</script>
<style scoped>.active{background:#e6f4ff}</style>
