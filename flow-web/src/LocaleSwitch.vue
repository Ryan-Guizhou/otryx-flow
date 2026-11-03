<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { setLocale, type AppLocale } from './i18n'

const { t, locale } = useI18n()

function choose(next: AppLocale): void {
  setLocale(next)
}
</script>

<template>
  <div class="locale-switch" role="group" :aria-label="t('common.language')">
    <button type="button" :class="{ selected: locale === 'zh-CN' }" :aria-pressed="locale === 'zh-CN'" @click="choose('zh-CN')">中文</button>
    <button type="button" :class="{ selected: locale === 'en' }" :aria-pressed="locale === 'en'" @click="choose('en')">English</button>
  </div>
</template>
