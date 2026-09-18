<template>
	<a-modal v-model:open="open" :title="formData.id ? '编辑数据源' : '新增数据源'" @ok="onSubmit" width="720px" destroy-on-close>
		<a-form :model="formData" :label-col="{ span: 5 }" :wrapper-col="{ span: 17 }">
			<a-form-item label="名称" required><a-input v-model:value="formData.name" /></a-form-item>
			<a-form-item label="类型" required>
				<a-select v-model:value="formData.type" :options="typeOptions" :disabled="!!formData.id" />
			</a-form-item>
			<a-form-item label="分级"><a-select v-model:value="formData.level" :options="['公开','内部','敏感','机密'].map(i=>({label:i,value:i}))" /></a-form-item>
			<template v-if="['mysql','pg'].includes(formData.type)">
				<a-form-item label="Host"><a-input v-model:value="formData.host" /></a-form-item>
				<a-form-item label="Port"><a-input-number v-model:value="formData.port" style="width:100%" /></a-form-item>
				<a-form-item label="Database"><a-input v-model:value="formData.databaseName" /></a-form-item>
				<a-form-item label="Username"><a-input v-model:value="formData.username" /></a-form-item>
				<a-form-item label="Password"><a-input-password v-model:value="formData.password" /></a-form-item>
			</template>
			<template v-if="formData.type==='kafka'">
				<a-form-item label="Bootstrap"><a-input v-model:value="formData.bootstrapServers" /></a-form-item>
				<a-form-item label="Topic"><a-input v-model:value="formData.topic" /></a-form-item>
			</template>
			<template v-if="formData.type==='s3'">
				<a-form-item label="Endpoint"><a-input v-model:value="formData.endpoint" /></a-form-item>
				<a-form-item label="Bucket"><a-input v-model:value="formData.bucket" /></a-form-item>
				<a-form-item label="AccessKey"><a-input v-model:value="formData.accessKey" /></a-form-item>
				<a-form-item label="SecretKey"><a-input-password v-model:value="formData.secretKey" /></a-form-item>
			</template>
			<template v-if="formData.type==='file'">
				<a-form-item label="路径"><a-input v-model:value="formData.filePath" /></a-form-item>
			</template>
			<template v-if="formData.type==='http_api'">
				<a-form-item label="URL"><a-input v-model:value="formData.httpUrl" /></a-form-item>
				<a-form-item label="AuthHeader"><a-input v-model:value="formData.authHeader" /></a-form-item>
			</template>
			<a-form-item label="用途"><a-input v-model:value="formData.purposes" placeholder='["ingest"]' /></a-form-item>
		</a-form>
	</a-modal>
</template>
<script setup>
	import { ref } from 'vue'
	import { message } from 'ant-design-vue'
	import datasourceApi from '@/api/lh/datasourceApi'
	const emit = defineEmits(['successful'])
	const open = ref(false)
	const formData = ref({})
	const typeOptions = ['mysql','pg','kafka','s3','file','http_api'].map(i => ({ label: i, value: i }))
	const onOpen = (record) => {
		formData.value = record ? { id: record.id, name: record.name, type: record.type, level: record.level, purposes: record.purposes } : { type: 'mysql', level: '内部', purposes: '["ingest"]', port: 3306 }
		open.value = true
	}
	const onSubmit = () => {
		datasourceApi.submitForm(formData.value, !!formData.value.id).then(() => {
			message.success('保存成功'); open.value = false; emit('successful')
		})
	}
	defineExpose({ onOpen })
</script>
