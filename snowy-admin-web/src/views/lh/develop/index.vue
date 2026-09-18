<template>
	<a-row :gutter="12">
		<a-col :span="6">
			<a-card title="脚本" size="small">
				<a-button type="link" @click="loadTree">刷新</a-button>
				<a-list :data-source="scripts" size="small">
					<template #renderItem="{ item }">
						<a-list-item style="cursor:pointer" @click="openScript(item)">{{ item.path }}</a-list-item>
					</template>
				</a-list>
			</a-card>
		</a-col>
		<a-col :span="18">
			<a-card title="SQL工作台" size="small">
				<a-input v-model:value="path" placeholder="脚本路径 如 ads/gmv.sql" style="margin-bottom:8px" />
				<a-textarea v-model:value="content" :rows="14" placeholder="SELECT ..." />
				<a-space style="margin-top:8px">
					<a-button type="primary" @click="onSave">保存</a-button>
					<a-button @click="onRun">stg试跑(Trino)</a-button>
				</a-space>
				<pre style="margin-top:12px;white-space:pre-wrap">{{ result }}</pre>
			</a-card>
		</a-col>
	</a-row>
</template>
<script setup>
	import { ref, onMounted } from 'vue'
	import { message } from 'ant-design-vue'
	import scriptApi from '@/api/lh/scriptApi'
	const scripts = ref([]); const id = ref(); const path = ref('ads/gmv.sql'); const content = ref('SELECT 1'); const result = ref('')
	const loadTree = () => scriptApi.tree({}).then((r) => { scripts.value = r || [] })
	const openScript = (item) => { id.value = item.id; path.value = item.path; content.value = item.content }
	const onSave = () => scriptApi.save({ id: id.value, path: path.value, content: content.value, ws: 'default' }).then((r) => { id.value = r.id; message.success('已保存'); loadTree() })
	const onRun = () => {
		if (!id.value) return message.warning('请先保存')
		scriptApi.runStg({ id: id.value }).then((r) => { result.value = JSON.stringify(r, null, 2) })
	}
	onMounted(loadTree)
</script>
