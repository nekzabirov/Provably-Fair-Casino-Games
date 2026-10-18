<script setup lang="ts">
defineProps<{
  initial: string
  avatarColor: string
  text: string
  accent?: string
  accentColor?: string
}>()
</script>

<template>
  <div class="pill">
    <span class="avatar" :style="{ background: avatarColor }">{{ initial }}</span>
    <span class="text">
      {{ text }}<span v-if="accent" class="accent" :style="{ color: accentColor }">{{ accent }}</span>
    </span>
  </div>
</template>

<style scoped>
.pill {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-radius: 10px;
  background: var(--tg-surface);
}

.avatar {
  flex: none;
  width: 24px;
  height: 24px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 9px;
  font-weight: 700;
}

.text {
  font-size: 10px;
  color: var(--tg-hint);
}

.accent {
  font-weight: 700;
}
</style>
