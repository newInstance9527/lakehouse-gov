import { baseRequest } from '@/utils/request'
/** 兼容路径：后端同时挂 /lh/query/* 与 /lh/compute/query/* */
const request = (url, ...arg) => baseRequest(`/lh/query/` + url, ...arg)
export default {
  exec(data) { return request('exec', data) },
  cancel(data) { return request('cancel', data) },
  history(data) { return request('history', data, 'get') },
  schemaTree() { return request('schemaTree', {}, 'get') },
  columns(params) { return request('columns', params, 'get') },
  export(data) { return request('export', data) },
}
