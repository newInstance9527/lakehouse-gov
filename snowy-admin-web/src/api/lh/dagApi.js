import { baseRequest } from '@/utils/request'
const request = (url, ...arg) => baseRequest(`/lh/dag/` + url, ...arg)
export default {
  page(data) { return request('page', data, 'get') },
  submitForm(data, edit = false) { return request(edit ? 'edit' : 'add', data) },
  delete(data) { return request('delete', data) },
  detail(data) { return request('detail', data, 'get') },
  graph(data) { return request('graph', data, 'get') },
  saveGraph(data) { return request('saveGraph', data) },
  nodeConfig(data) { return request('nodeConfig', data) },
  validate(data) { return request('validate', data) },
  deploy(data) { return request('deploy', data) },
  etlEngines() { return request('etlEngines', {}, 'get') },
  recommendEngine(data) { return request('recommendEngine', data) }
}
