<script setup lang="ts">
import { PhWarningCircle } from '@phosphor-icons/vue'

defineProps<{
  title: string
  detail: string
}>()

/** The player cannot fix a session or currency fault from inside the game, and
 *  the game is a frame inside the host page, so a reload is the only real action
 *  we can offer. Offering nothing leaves them on a dead end. */
function retry(): void {
  window.location.reload()
}
</script>

<template>
  <div class="error">
    <div class="card">
      <div class="icon"><PhWarningCircle :size="30" weight="fill" /></div>
      <h1 class="title">{{ title }}</h1>
      <p class="detail">{{ detail }}</p>
      <button class="retry press" type="button" @click="retry">Try again</button>
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
  background: var(--c-bg);
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
  width: 60px;
  height: 60px;
  margin-bottom: 2px;
  border-radius: var(--r-ctl);
  background: var(--c-lose-soft);
  color: var(--c-lose);
}

.title {
  margin: 0;
  color: var(--c-text);
  font-size: 21px;
  font-weight: 700;
  letter-spacing: -0.01em;
}

.detail {
  margin: 0;
  color: var(--c-text-2);
  font-size: 14px;
  font-weight: 500;
  line-height: 1.45;
}

.retry {
  margin-top: 10px;
  height: var(--tap);
  padding: 0 26px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--r-ctl);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
}

.retry:active {
  background: var(--c-surface-2);
}
</style>
