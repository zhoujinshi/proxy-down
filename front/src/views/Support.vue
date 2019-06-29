<template>
  <div>
    <Card>
      <p slot="title">金石编译：20190701</p>
      <div class="ivu-card-body">
        <b slot="title">编译</b>
        <pre>git clone https://github.com/zhoujinshi/proxy-down.git
cd proxy-down/front
#build html
npm install
npm run build
cd ../main
mvn clean package -DskipTests -Pprd</pre>
        <b slot="title">运行</b>
        <pre>java -jar proxy-down-main.jar</pre>
      </div>
    </Card>
  </div>
</template>

<script>
  import {
    openUrl
  } from '../common/native.js'
  export default {
    data() {
      return {

      }
    },
    methods: {
      openUrl(url) {
        openUrl(url)
      }
    },
    created() {

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