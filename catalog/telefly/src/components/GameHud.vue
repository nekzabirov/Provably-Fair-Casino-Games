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
/* The flight fills the screen behind the chrome, so the HUD is a pair of
   floating chips rather than a bar: no band of dead surface at the top. */
.hud {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: calc(10px + var(--safe-top)) var(--pad-x) 6px;
}

/* The balance is a readout, not a control, so it carries no accent border: the
   only accented thing on screen should be the action the player can take. */
.balance {
  display: flex;
  align-items: center;
  gap: 7px;
  height: var(--tap);
  padding: 0 15px;
  border-radius: var(--r-chip);
  background: rgba(21, 30, 42, 0.72);
  border: 1px solid var(--c-line);
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
    background: var(--c-surface);
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
