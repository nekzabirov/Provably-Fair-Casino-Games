<script setup lang="ts">
import { PhWarning } from '@phosphor-icons/vue'

defineProps<{
  title: string
  detail: string
}>()
</script>

<template>
  <div class="error">
    <div class="card">
      <div class="icon"><PhWarning :size="28" weight="fill" /></div>
      <h1 class="title">{{ title }}</h1>
      <p class="detail">{{ detail }}</p>
    </div>
  </div>
</template>

<style scoped>
.error {
  width: 100%;
  height: 100dvh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--tg-bg);
}

.card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  max-width: 320px;
  text-align: center;
}

.icon {
  width: 56px;
  height: 56px;
  border-radius: var(--r-chip);
  background: var(--c-lose-soft);
  color: var(--c-lose);
  display: flex;
  align-items: center;
  justify-content: center;
}

.title {
  margin: 0;
  color: var(--tg-text);
  font-size: 20px;
  font-weight: 800;
}

.detail {
  margin: 0;
  color: var(--tg-hint);
  font-size: 14px;
  line-height: 1.4;
}
</style>
