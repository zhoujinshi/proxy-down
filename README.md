> Proxy Down 是一款开源的免费 HTTP 高速下载器，底层使用`netty`开发，支持自定义 HTTP 请求下载且支持扩展功能，可以通过安装扩展实现特殊的下载需求。

## 开发

本项目后端主要使用 `java` + `spring` + `boot` + `netty`，前端使用 `vue.js` + `iview`

### 环境
![](https://img.shields.io/badge/JAVA-1.8%2B-brightgreen.svg) ![](https://img.shields.io/badge/maven-3.0%2B-brightgreen.svg) ![](https://img.shields.io/badge/node.js-8.0%2B-brightgreen.svg)

### 编译

```
git clone https://github.com/zhoujinshi/proxy-down.git
cd proxy-down/front
#build html
npm install
npm run build
cd ../main
mvn clean package -DskipTests -Pprd
```

### 运行
```
java -jar proxy-down-main.jar
```
