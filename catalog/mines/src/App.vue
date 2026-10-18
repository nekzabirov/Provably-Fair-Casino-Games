<script setup lang="ts">
import { onMounted } from 'vue'
import { config } from './config'
import { currencyRule } from './currency'
import { forceHideSplash } from './preload'
import GameScreen from './components/GameScreen.vue'
import ErrorScreen from './components/ErrorScreen.vue'

const rule = currencyRule(config.currency)

// On an unsupported currency GameScreen never mounts (so it never dismisses the
// splash) — hide it here, otherwise it would hang over the error screen.
onMounted(() => {
  if (!rule) forceHideSplash()
})
</script>

<template>
  <GameScreen v-if="rule" />
  <ErrorScreen
    v-else
    title="Unsupported currency"
    :detail="`“${config.currency || '-'}” is not supported. Only TON is supported.`"
  />
</template>
