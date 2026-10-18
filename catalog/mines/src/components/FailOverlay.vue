<script setup lang="ts">
const emit = defineEmits<{ dismiss: [] }>()
</script>

<template>
  <div class="fail" @click="emit('dismiss')">
    <div class="flash" />
    <div class="shock" />
    <div class="content">
      <img class="mine" src="/mine.webp" alt="" aria-hidden="true" />
      <div class="title">Mine hit</div>
      <div class="subtitle">The round is over. Your stake is lost.</div>
      <div class="hint">Tap anywhere to continue</div>
    </div>
  </div>
</template>

<style scoped>
.fail {
  position: absolute;
  inset: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(11, 16, 23, 0.78);
  backdrop-filter: blur(3px);
  -webkit-backdrop-filter: blur(3px);
  animation: veil 0.3s var(--e-out) both;
  cursor: pointer;
}

@keyframes veil {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

.flash {
  position: absolute;
  inset: 0;
  background: radial-gradient(circle at center, rgba(255, 90, 101, 0.5), rgba(255, 90, 101, 0) 62%);
  animation: flash 0.7s var(--e-out) forwards;
}

@keyframes flash {
  0% {
    opacity: 0;
  }
  10% {
    opacity: 1;
  }
  100% {
    opacity: 0;
  }
}

.shock {
  position: absolute;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  border: 4px solid rgba(255, 90, 101, 0.85);
  animation: shock 0.7s var(--e-out) forwards;
}

@keyframes shock {
  0% {
    transform: scale(0.2);
    opacity: 1;
  }
  100% {
    transform: scale(16);
    opacity: 0;
  }
}

.content {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 0 32px;
  text-align: center;
  animation: pop 0.42s cubic-bezier(0.2, 1.3, 0.4, 1) both;
}

/* Starts at 0.72, never at 0: nothing in the physical world grows out of
   literally nothing, and a from-zero pop reads as a glitch. */
@keyframes pop {
  0% {
    transform: scale(0.72);
    opacity: 0;
  }
  100% {
    transform: scale(1);
    opacity: 1;
  }
}

.mine {
  width: 108px;
  height: 108px;
  margin-bottom: 6px;
  animation: jolt 0.5s var(--e-out);
}

@keyframes jolt {
  0%,
  100% {
    transform: translate(0, 0) rotate(0);
  }
  20% {
    transform: translate(-8px, 4px) rotate(-6deg);
  }
  45% {
    transform: translate(8px, -4px) rotate(6deg);
  }
  70% {
    transform: translate(-5px, 2px) rotate(-3deg);
  }
}

.title {
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--c-lose);
}

.subtitle {
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text-2);
  max-width: 22ch;
}

.hint {
  margin-top: 16px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--c-text-3);
}

@media (prefers-reduced-motion: reduce) {
  .fail,
  .flash,
  .content,
  .mine {
    animation: none;
  }

  .shock {
    display: none;
  }
}
</style>
