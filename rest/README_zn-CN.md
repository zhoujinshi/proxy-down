# HTTP downloader RESTful server

## 编译
```
git clone git@github.com:zhoujinshi/pdown-rest.git
cd pdown-rest
mvn clean package -Dmaven.test.skip=true -Pexec
```

## 启动

启动服务需要指定根目录,若为空时会默认使用程序运行所在的目录。  

```
// 使用默认目录
java -jar pdown-rest.jar

// 使用指定的目录
java -jar pdown-rest.jar -b=f:/down/rest
```



