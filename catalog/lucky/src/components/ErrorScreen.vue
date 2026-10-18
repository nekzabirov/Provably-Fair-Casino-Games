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
      <span class="icon"><PhWarning :size="28" weight="fill" /></span>
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
  background: var(--warm-bg);
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
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: var(--r-chip);
  background: var(--c-lose-soft);
  color: var(--c-lose);
}

.title {
  margin: 0;
  color: var(--c-text);
  font-size: 20px;
  font-weight: 700;
}

.detail {
  margin: 0;
  color: var(--c-text-2);
  font-size: 14px;
  line-height: 1.4;
}
</style>
