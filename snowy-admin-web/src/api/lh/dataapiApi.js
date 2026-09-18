import { baseRequest } from '@/utils/request'
const request = (url, ...arg) => baseRequest(`/lh/dataapi/` + url, ...arg)
export default {
  page(data) { return request('page', data, 'get') },
  submitForm(data, edit = false) { return request(edit ? 'edit' : 'add', data) },
  delete(data) { return request('delete', data) },
  detail(data) { return request('detail', data, 'get') },
  trial(data) { return request('trial', data) },
  publish(data) { return request('publish', data) },
  retire(data) { return request('retire', data) },
  embedUrl() { return request('embedUrl', {}, 'get') }
}
