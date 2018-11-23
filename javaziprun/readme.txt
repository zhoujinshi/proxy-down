GreenJVMMake.jar说明：

这是一个rt.jar精简工具，用于动态截取我们应用中使用到的rt.jar下class，并仅将使用到的class重新组成rt.jar。

example1(传参调用) : java -jar GreenJVMMake.jar -t da -i ./fps_test.jar -o ./

-t 精简的应用类型,DA为桌面应用,CA为命令行应用

-i 我们的执行文件所在路径

-o 精简后的rt.jar输出目录

example2(命令行调用) : java -jar GreenJVMMake.jar

Simplify the type [da( Desktop Application ) or ca( Console Application )] :
da
My application file :
./fps_test.jar
Output jar folder :
./

有问题的可以到http://blog.csdn.net/cping1982我的博客上留言。