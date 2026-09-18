<template>
	<a-modal v-model:open="open" :title="formData.id?'编辑API绑定':'新增API绑定'" @ok="onSubmit" destroy-on-close>
		<a-form :model="formData" :label-col="{span:6}" :wrapper-col="{span:16}">
			<a-form-item label="名称" required><a-input v-model:value="formData.name" /></a-form-item>
			<a-form-item label="对外路径" required><a-input v-model:value="formData.publicPath" placeholder="/api/v1/gmv/daily" /></a-form-item>
			<a-form-item label="方法"><a-select v-model:value="formData.method" :options="['GET','POST'].map(i=>({label:i,value:i}))" /></a-form-item>
			<a-form-item label="SQLREST API" required><a-input v-model:value="formData.sqlrestApiId" /></a-form-item>
			<a-form-item label="来源类型" required><a-select v-model:value="formData.sourceKind" :options="['asset','metric','sql'].map(i=>({label:i,value:i}))" /></a-form-item>
			<a-form-item label="来源引用"><a-input v-model:value="formData.sourceRef" placeholder="iceberg.ads_trade.ads_gmv_board" /></a-form-item>
		</a-form>
	</a-modal>
</template>
<script setup>
	import { ref } from 'vue'
	import { message } from 'ant-design-vue'
	import dataapiApi from '@/api/lh/dataapiApi'
	const emit = defineEmits(['successful'])
	const open = ref(false)
	const formData = ref({})
	const onOpen = (record) => {
		formData.value = record ? { ...record } : { method: 'GET', sourceKind: 'asset', publicPath: '/api/v1/gmv/daily' }
		open.value = true
	}
	const onSubmit = () => dataapiApi.submitForm(formData.value, !!formData.value.id).then(() => { message.success('保存成功'); open.value = false; emit('successful') })
	defineExpose({ onOpen })
</script>
