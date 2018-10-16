<template>
  <div class="v-about">
      <p slot="title">金哥编译版。</p>
  </div>
</template>
<script>
import { openUrl } from '../common/native.js'

export default {
  name: 'about',
  methods: {
    openUrl(url) {
      openUrl(url)
    }
  }
}
</script>
