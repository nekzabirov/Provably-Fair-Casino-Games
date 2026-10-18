<script setup lang="ts">
import { onMounted } from 'vue'
import { config } from './config'
import { currencyRule } from './currency'
import { forceHideSplash } from './preload'
import GameScreen from './components/GameScreen.vue'
import ErrorScreen from './components/ErrorScreen.vue'

const rule = currencyRule(config.currency)

// PvP is TON-only. On an unsupported currency GameScreen never mounts (so it
// never dismisses the splash) - hide it here, otherwise it would hang.
onMounted(() => {
  if (!rule) forceHideSplash()
})
</script>

<template>
  <GameScreen v-if="rule" />
  <ErrorScreen
    v-else
    title="Unsupported currency"
    :detail="`PvP is played in TON only - “${config.currency || '-'}” is not supported.`"
  />
</template>
