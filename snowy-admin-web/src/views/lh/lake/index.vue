<template>
	<a-card :bordered="false">
		<a-space style="margin-bottom:12px">
			<a-statistic v-for="(v,k) in stats.iceberg || {}" :key="k" :title="k" :value="v" style="margin-right:24px" />
			<a-statistic title="CK热表" :value="stats.clickhouseHot || 0" />
		</a-space>
		<a-tabs>
			<a-tab-pane key="ice" tab="Iceberg ODS/DWD/DWS/ADS">
				<a-space style="margin-bottom:8px">
					<a-select v-model:value="layer" allow-clear placeholder="分层" style="width:120px" :options="['ODS','DWD','DWS','ADS'].map(i=>({label:i,value:i}))" />
					<a-button type="primary" @click="iceRef.refresh(true)">查询</a-button>
				</a-space>
				<s-table ref="iceRef" :columns="iceCols" :data="loadIce" bordered :row-key="(r)=>r.id" />
			</a-tab-pane>
			<a-tab-pane key="ck" tab="ClickHouse热ADS">
				<s-table ref="ckRef" :columns="ckCols" :data="loadCk" bordered :row-key="(r)=>r.id" />
			</a-tab-pane>
		</a-tabs>
	</a-card>
</template>
<script setup>
	import { ref, onMounted } from 'vue'
	import lakeApi from '@/api/lh/lakeApi'
	const iceRef = ref(); const ckRef = ref(); const layer = ref(); const stats = ref({})
	const iceCols = [{title:'FQTN',dataIndex:'fqtn'},{title:'分层',dataIndex:'layer',width:80},{title:'Location',dataIndex:'location'},{title:'状态',dataIndex:'status',width:90}]
	const ckCols = [{title:'表名',dataIndex:'tableName'},{title:'对应Iceberg',dataIndex:'icebergFqtn'},{title:'TTL天',dataIndex:'ttlDays',width:90},{title:'状态',dataIndex:'status',width:90}]
	const loadIce = (p) => lakeApi.icebergPage(Object.assign(p, { layer: layer.value }))
	const loadCk = (p) => lakeApi.ckPage(p)
	onMounted(() => lakeApi.layerStats().then((r) => { stats.value = r || {} }))
</script>
