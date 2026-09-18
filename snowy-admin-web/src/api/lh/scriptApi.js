import { baseRequest } from '@/utils/request'
const request = (url, ...arg) => baseRequest(`/lh/script/` + url, ...arg)
export default {
  tree(data) { return request('tree', data, 'get') },
  save(data) { return request('save', data) },
  runStg(data) { return request('runStg', data) },
  runs(data) { return request('runs', data, 'get') }
}
