# HTTP downloader RESTful server

## Build
```
git clone git@github.com:zhoujinshi/pdown-rest.git
cd pdown-rest
mvn clean package -Dmaven.test.skip=true -Pexec
```

## Start

The startup service needs to specify the root directory. If it is empty, it will default to the directory where the program is running.

```
// Use default root path
java -jar pdown-rest.jar

// Specify the root path
java -jar pdown-rest.jar -b=f:/down/rest
```
