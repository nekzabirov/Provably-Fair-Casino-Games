<script setup lang="ts">
defineProps<{
  value: number | string
  unit: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  decrement: []
  increment: []
}>()
</script>

<template>
  <div class="card" :class="{ disabled }">
    <div class="info">
      <span class="value">{{ value }}</span>
      <span class="unit">{{ unit }}</span>
    </div>
    <div class="btns">
      <button class="btn" type="button" :disabled="disabled" @click="emit('decrement')">−</button>
      <button class="btn" type="button" :disabled="disabled" @click="emit('increment')">+</button>
    </div>
  </div>
</template>

<style scoped>
.card {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px;
  border-radius: 14px;
  background: var(--tg-surface);
  border: 1px solid var(--tg-divider);
  -webkit-tap-highlight-color: transparent;
}

.card.disabled {
  opacity: 0.55;
}

.info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-left: 6px;
}

.value {
  font-size: 22px;
  font-weight: 700;
  color: var(--tg-text);
  line-height: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.unit {
  font-size: 11px;
  font-weight: 700;
  color: var(--accent);
  letter-spacing: 0.04em;
}

.btns {
  display: flex;
  gap: 6px;
}

.btn {
  width: 46px;
  height: 46px;
  border: none;
  border-radius: 10px;
  background: rgba(42, 171, 238, 0.14);
  color: var(--accent);
  font-size: 22px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  touch-action: manipulation;
  user-select: none;
  -webkit-user-select: none;
}

.btn:disabled {
  cursor: default;
}

.btn:not(:disabled):active {
  background: rgba(42, 171, 238, 0.26);
}
</style>
