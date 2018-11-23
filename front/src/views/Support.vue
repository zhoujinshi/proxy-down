<template>
  <div>
    <Card style="margin-top:20px;">
      <p slot="title">2018-11-23 编译</p>
    </Card>
  </div>
</template>

<script>
import { openUrl } from '../common/native.js'
export default {
  data() {
    return {
      softList: []
    }
  },
  methods: {
    openUrl(url) {
      openUrl(url)
    }
  },
  created() {
    // this.$noSpinHttp.get(this.$config.adminServer + 'recommend/soft').then(result => {
    //   this.softList = result.data
    // })
  }
}
</script>

<style scoped>
.card-container {
  width: 220px;
  text-align: center;
  margin: 5px;
}

.qr-container {
  display: inline-block;
}

.recommend-container {
  display: inline-flex;
  height: 300px;
}

.card-container b {
  display: inline-block;
  padding-top: 5px;
}

.card-container img {
  width: 200px;
  height: 200px;
}
</style>
