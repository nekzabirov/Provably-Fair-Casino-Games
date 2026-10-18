<script setup lang="ts">
import { pad2 } from '../format'

/**
 * A glossy lottery ball. The signature object of the game. Built from layered
 * radial gradients rather than an image so it stays crisp at any size and can be
 * animated (pop, flip) without swapping textures.
 *
 * variant:
 *   red   - a number the player has chosen
 *   gold  - a chosen number that matched a drawn number (the prize colour)
 *   white - an unchosen number on the grid (a flat cream tile, not a ball)
 *   drawn - a number the draw pulled that the player does NOT hold
 */
withDefaults(
  defineProps<{
    value: number
    variant?: 'red' | 'gold' | 'white' | 'drawn'
    size?: number
    /** Show the two-digit form (01..49) instead of the bare number. */
    pad?: boolean
  }>(),
  { variant: 'red', size: 44, pad: false },
)
</script>

<template>
  <span
    class="ball"
    :class="`ball--${variant}`"
    :style="{ width: `${size}px`, height: `${size}px`, fontSize: `${Math.round(size * 0.42)}px` }"
  >
    <span class="ball__n tnum">{{ pad ? pad2(value) : value }}</span>
  </span>
</template>

<style scoped>
.ball {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-weight: 800;
  line-height: 1;
  letter-spacing: -0.02em;
  flex: none;
  isolation: isolate;
}

.ball__n {
  position: relative;
  z-index: 2;
}

/* The specular highlight: a small bright spot up and to the left, the single
   cue that reads a flat disc as a spherical, glossy ball. */
.ball::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: 50%;
  z-index: 1;
  background: radial-gradient(
    42% 34% at 33% 26%,
    rgba(255, 255, 255, 0.92) 0%,
    rgba(255, 255, 255, 0.28) 26%,
    transparent 58%
  );
  pointer-events: none;
}

/* Red chosen ball — Pencil node RZ55W: radial highlight at 40%/35%. */
.ball--red {
  color: #fff;
  background: radial-gradient(120% 120% at 40% 35%, #f35555 0%, #d01515 55%, #980808 100%);
  box-shadow:
    inset 0 -3px 6px rgba(0, 0, 0, 0.35),
    inset 0 2px 4px rgba(255, 255, 255, 0.3),
    0 1px 2px rgba(0, 0, 0, 0.2);
}

/* Gold matched ball. */
.ball--gold {
  color: var(--ink-bright);
  background:
    radial-gradient(120% 120% at 32% 24%, var(--gold-hi) 0%, var(--gold) 40%, var(--gold-2) 74%, var(--gold-deep) 100%);
  box-shadow:
    inset 0 -3px 6px rgba(90, 55, 0, 0.5),
    inset 0 2px 4px rgba(255, 255, 255, 0.55),
    0 3px 10px rgba(247, 206, 85, 0.4);
}

/* Unchosen number — Pencil node "Cell": a flat white tile, hairline border,
   faint drop shadow, dark bold digit. Not a sphere. */
.ball--white {
  color: #333333;
  border-radius: 8px;
  font-weight: 700;
  background: #fbfbfb;
  box-shadow:
    inset 0 0 0 1px #e2e2e2,
    0 1px 1.5px rgba(0, 0, 0, 0.06);
}

.ball--white::before {
  display: none;
}

/* A number the draw pulled that is not on the player's ticket: a dark glassy
   disc, present but clearly not theirs. */
.ball--drawn {
  color: var(--lot-text-2);
  background: radial-gradient(120% 120% at 32% 24%, #3a1417 0%, #260a0c 60%, #160406 100%);
  box-shadow:
    inset 0 -2px 5px rgba(0, 0, 0, 0.5),
    inset 0 1px 2px rgba(255, 255, 255, 0.12),
    0 2px 5px rgba(0, 0, 0, 0.4);
}

.ball--drawn::before {
  background: radial-gradient(42% 34% at 33% 26%, rgba(255, 255, 255, 0.3) 0%, transparent 58%);
}
</style>
