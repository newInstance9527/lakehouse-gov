import { baseRequest } from '@/utils/request'
const request = (url, ...arg) => baseRequest(`/lh/engine/` + url, ...arg)
export default {
  flinkJobs() { return request('flinkJobs', {}, 'get') },
  dsWorkflows() { return request('dsWorkflows', {}, 'get') },
  health() { return request('health', {}, 'get') }
}
