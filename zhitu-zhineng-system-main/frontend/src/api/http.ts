import axios from 'axios'
import type { ApiResponse } from '@/types'

const http = axios.create({
  baseURL: '/api',
  timeout: 30000
})

http.interceptors.response.use(
  response => {
    const body = response.data as ApiResponse<unknown>
    if (body && body.success === false) {
      return Promise.reject(new Error(body.message || '请求处理失败'))
    }
    return body?.data ?? response.data
  },
  error => Promise.reject(
    new Error(error.response?.data?.message || error.message || '网络请求失败')
  )
)

export default http
