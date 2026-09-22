/**
 * 生命周期治理 API（与演示门户 /lh/lifecycle 同源）
 */
import { baseRequest } from '@/utils/request'

const request = (url, ...arg) => baseRequest(`/lh/lifecycle/` + url, ...arg)

export default {
	overview(params) {
		return request('overview', params || {}, 'get')
	},
	jobsLatest(params) {
		return request('jobs/latest', params || {}, 'get')
	},
	topStorage(params) {
		return request('top-storage', params || {}, 'get')
	},
	stats(params) {
		return request('stats', params || {}, 'get')
	},
	policies(params) {
		return request('policies', params || {}, 'get')
	},
	policy(params) {
		return request('policy', params || {}, 'get')
	},
	upsertPolicy(data) {
		return request('policies', data, 'put')
	},
	compact(data) {
		return request('compact', data)
	},
	expire(data) {
		return request('expire', data)
	},
	orphanScan(data) {
		return request('orphan/scan', data)
	},
	runNow(data) {
		return request('jobs/run-now', data)
	},
	runs(params) {
		return request('runs', params || {}, 'get')
	},
	syncRun(runId) {
		return request(`runs/sync?runId=${encodeURIComponent(runId)}`, {})
	},
	storageSummary(params) {
		return request('storage/summary', params || {}, 'get')
	},
	storageAdvice(params) {
		return request('storage/advice', params || {}, 'get')
	}
}
