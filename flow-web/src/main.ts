import { createApp } from 'vue'
import App from './App.vue'
import { applyDocumentLocale, i18n } from './i18n'
import './styles.css'

applyDocumentLocale()
createApp(App).use(i18n).mount('#app')
