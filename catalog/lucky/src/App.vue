<script setup lang="ts">
import { config } from './config'
import { currencyRule } from './currency'
import GameScreen from './components/GameScreen.vue'
import ErrorScreen from './components/ErrorScreen.vue'

const rule = currencyRule(config.currency)
</script>

<template>
  <GameScreen v-if="rule" />
  <ErrorScreen
    v-else
    title="Unsupported currency"
    :detail="`“${config.currency || '-'}” is not supported. Only TON is supported.`"
  />
</template>
