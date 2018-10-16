package com.github.zhoujinshi.proxy;

import com.github.zhoujinshi.proxy.intercept.HttpProxyInterceptInitializer;
import com.github.zhoujinshi.proxy.intercept.HttpProxyInterceptPipeline;
import com.github.zhoujinshi.proxy.intercept.common.FullResponseIntercept;
import com.github.zhoujinshi.proxy.server.HttpProxyServer;
import com.github.zhoujinshi.proxy.server.HttpProxyServerConfig;
import com.github.zhoujinshi.proxy.util.HttpUtil;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.nio.charset.Charset;

public class InterceptResponseContentHttpProxyServer {

  public static void main(String[] args) throws Exception {
    HttpProxyServerConfig config =  new HttpProxyServerConfig();
    config.setHandleSsl(true);
    new HttpProxyServer()
        .serverConfig(config)
        .proxyInterceptInitializer(new HttpProxyInterceptInitializer() {
          @Override
          public void init(HttpProxyInterceptPipeline pipeline) {
            pipeline.addLast(new FullResponseIntercept() {

              @Override
              public boolean match(HttpRequest httpRequest, HttpResponse httpResponse, HttpProxyInterceptPipeline pipeline) {
                //在匹配到百度首页时插入js
                return HttpUtil.checkUrl(pipeline.getHttpRequest(), "^www.baidu.com$")
                    && isHtml(httpRequest, httpResponse);
              }

              @Override
              public void handelResponse(HttpRequest httpRequest, FullHttpResponse httpResponse, HttpProxyInterceptPipeline pipeline) {
                //打印原始响应信息
                System.out.println(httpResponse.toString());
                System.out.println(httpResponse.content().toString(Charset.defaultCharset()));
                //修改响应头和响应体
                httpResponse.headers().set("handel", "edit head");
                /*int index = ByteUtil.findText(httpResponse.content(), "<head>");
                ByteUtil.insertText(httpResponse.content(), index, "<script>alert(1)</script>");*/
                httpResponse.content().writeBytes("<script>alert('hello proxy')</script>".getBytes());
              }
            });
          }
        })
        .start(9999);
  }
}
