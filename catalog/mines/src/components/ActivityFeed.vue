<script setup lang="ts">
import ActivityPill from './ActivityPill.vue'

// Static sample feed for now; fed by live events later.
const items = [
  { initial: 'U', avatarColor: '#4A6A8A', text: 'usernam found ', accent: 'diamond x2.1', accentColor: '#3AB0FF' },
  { initial: 'M', avatarColor: '#6A4A8A', text: 'munita700 hit mine', accent: ' 🔥', accentColor: '#FF3B30' },
]
</script>

<template>
  <div class="feed">
    <ActivityPill v-for="(item, i) in items" :key="i" v-bind="item" />
  </div>
</template>

<style scoped>
.feed {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px 16px;
}
</style>
