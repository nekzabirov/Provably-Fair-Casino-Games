<script setup lang="ts">
import TonIcon from './TonIcon.vue'

defineProps<{
  /** Pre-formatted balance (GameScreen does the currency math). */
  balance: string
}>()
</script>

<template>
  <header class="hud">
    <div class="balance">
      <span class="balance__v tnum">{{ balance }}</span>
      <TonIcon :size="15" />
    </div>

    <div class="actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.hud {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: calc(10px + var(--safe-top)) var(--pad-x) 8px;
}

/* The balance is a readout, not a control, so it carries no accent border: the
   only accented thing on screen should be the action the player can take.
   It floats over a lit 3D render, so the fill is opaque enough to hold the
   text at AA on the brightest part of the table. */
.balance {
  display: flex;
  align-items: center;
  gap: 7px;
  height: var(--tap);
  padding: 0 15px;
  border-radius: var(--r-chip);
  background: var(--warm-veil);
  border: 1px solid var(--c-line-2);
  box-shadow: var(--sh-1);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  font-size: 19px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--c-text);
  line-height: 1;
  white-space: nowrap;
  overflow: hidden;
}

.balance__v {
  overflow: hidden;
  text-overflow: ellipsis;
}

.actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

@media (prefers-reduced-transparency: reduce) {
  .balance {
    background: var(--warm-surface);
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
