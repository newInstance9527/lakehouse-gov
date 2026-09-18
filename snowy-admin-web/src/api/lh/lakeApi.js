import { baseRequest } from '@/utils/request'
const ice = (url, ...arg) => baseRequest(`/lh/lake/iceberg/` + url, ...arg)
const ck = (url, ...arg) => baseRequest(`/lh/lake/ck/` + url, ...arg)
const lake = (url, ...arg) => baseRequest(`/lh/lake/` + url, ...arg)
export default {
  icebergPage(data) { return ice('page', data, 'get') },
  icebergSubmit(data, edit = false) { return ice(edit ? 'edit' : 'add', data) },
  icebergDelete(data) { return ice('delete', data) },
  icebergDetail(data) { return ice('detail', data, 'get') },
  ckPage(data) { return ck('page', data, 'get') },
  ckSubmit(data, edit = false) { return ck(edit ? 'edit' : 'add', data) },
  ckDelete(data) { return ck('delete', data) },
  ckDetail(data) { return ck('detail', data, 'get') },
  layerStats() { return lake('layerStats', {}, 'get') }
}
