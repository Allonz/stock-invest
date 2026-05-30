// Axios 实例封装 - 统一的 HTTP 请求实例
import axios from 'axios'
import type { AxiosInstance, AxiosResponse } from 'axios'

// 创建 axios 实例, baseURL 为空, 走 Vite proxy
const request: AxiosInstance = axios.create({
  baseURL: '',
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 通用 API 响应格式
export interface ApiResponse<T = any> {
  success: boolean
  data: T
  timestamp: string
}

// 响应拦截器 - 统一处理错误
request.interceptors.response.use(
  (response: AxiosResponse<ApiResponse>) => {
    return response
  },
  (error) => {
    // 将错误消息统一格式化, 方便页面层用 notification 展示
    const message = error.response?.data?.message || error.message || '网络错误'
    return Promise.reject(new Error(message))
  }
)

export default request
