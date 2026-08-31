import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import { i18n } from './i18n'
import { reveal } from './directives/reveal'
import './styles/global.css'

const app = createApp(App)
app.directive('reveal', reveal)
app.use(router)
app.use(i18n)
app.use(ElementPlus)
app.mount('#app')
