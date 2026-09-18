<template>
	<a-row :gutter="12">
		<a-col :span="8"><a-card title="健康"><pre>{{ health }}</pre></a-card></a-col>
		<a-col :span="8"><a-card title="Flink Jobs"><pre>{{ flink }}</pre></a-card></a-col>
		<a-col :span="8"><a-card title="DS Workflows"><pre>{{ ds }}</pre></a-card></a-col>
	</a-row>
</template>
<script setup>
	import { ref, onMounted } from 'vue'
	import engineApi from '@/api/lh/engineApi'
	const health = ref(''); const flink = ref(''); const ds = ref('')
	onMounted(() => {
		engineApi.health().then((r) => { health.value = JSON.stringify(r, null, 2) })
		engineApi.flinkJobs().then((r) => { flink.value = JSON.stringify(r, null, 2) })
		engineApi.dsWorkflows().then((r) => { ds.value = JSON.stringify(r, null, 2) })
	})
</script>
