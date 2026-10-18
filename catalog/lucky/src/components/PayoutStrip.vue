<script setup lang="ts">
import TonIcon from './TonIcon.vue'

defineProps<{
  /** Pre-formatted stake x2 - what the round returns on a correct pick. */
  toWin: string
}>()
</script>

<template>
  <!-- The whole proposition on one line, shown before the player commits and kept
       on screen while they pick. The odds are stated as plainly as the payout:
       one cup of three holds the ball, so a 2x return is not a favourable bet and
       the player should be able to see that without doing the arithmetic. -->
  <div class="strip">
    <div class="cell">
      <span class="cell__l">pays</span>
      <span class="cell__v tnum">2.00x</span>
    </div>

    <span class="sep" aria-hidden="true" />

    <div class="cell">
      <span class="cell__l">chance</span>
      <span class="cell__v tnum">1 in 3</span>
    </div>

    <span class="sep" aria-hidden="true" />

    <div class="cell">
      <span class="cell__l">to win</span>
      <span class="cell__v cell__v--win">
        <span class="tnum">{{ toWin }}</span>
        <TonIcon :size="13" />
      </span>
    </div>
  </div>
</template>

<style scoped>
.strip {
  display: flex;
  align-items: center;
  height: 42px;
  padding: 0 6px;
  border-radius: var(--r-ctl);
  background: var(--warm-surface);
  border: 1px solid var(--c-line);
}

.cell {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
}

.cell__l {
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--c-text-3);
  line-height: 1;
}

.cell__v {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
  font-weight: 700;
  color: var(--c-text);
  line-height: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* The one number the player is playing for; copper marks it as the upside. */
.cell__v--win {
  color: var(--copper);
}

.sep {
  flex: 0 0 auto;
  width: 1px;
  height: 22px;
  background: var(--c-line-2);
}
</style>
