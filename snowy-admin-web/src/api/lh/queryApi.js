import { baseRequest } from '@/utils/request'
const request = (url, ...arg) => baseRequest(`/lh/query/` + url, ...arg)
export default {
  exec(data) { return request('exec', data) },
  cancel(data) { return request('cancel', data) },
  history(data) { return request('history', data, 'get') },
  schemaTree() { return request('schemaTree', {}, 'get') }
}
