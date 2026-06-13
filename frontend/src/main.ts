import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import naive from 'naive-ui'
import './styles/global.css'

const app = createApp(App)

// 过滤 naive-ui 内部 transition slot 警告（第三方库内部问题，不影响功能）
app.config.warnHandler = (msg, instance, trace) => {
  if (msg.includes('Slot "default" invoked outside of the render function')) return
  console.warn(msg, trace)
}

app.use(router)
app.use(naive)
app.mount('#app')
