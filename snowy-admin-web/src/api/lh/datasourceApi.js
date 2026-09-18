import { baseRequest } from '@/utils/request'
const request = (url, ...arg) => baseRequest(`/lh/datasource/` + url, ...arg)
export default {
  page(data) { return request('page', data, 'get') },
  submitForm(data, edit = false) { return request(edit ? 'edit' : 'add', data) },
  delete(data) { return request('delete', data) },
  detail(data) { return request('detail', data, 'get') },
  test(data) { return request('test', data) },
  previewSchema(data) { return request('previewSchema', data, 'get') },
  purposes(data) { return request('purposes', data) },
  bindings(data) { return request('bindings', data, 'get') },
  rotateCred(data) { return request('rotateCred', data) },
  listForDag() { return request('listForDag', {}, 'get') },
  supersetProjection() { return request('supersetProjection', {}, 'get') }
}
