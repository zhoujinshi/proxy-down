package org.pdown.core.boot;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.internal.StringUtil;
import org.pdown.core.entity.HttpRequestInfo;
import org.pdown.core.entity.HttpResponseInfo;
import org.pdown.core.exception.BootstrapBuildException;
import org.pdown.core.util.HttpDownUtil;
import java.util.Map;

/**
 * 通过URL创建一个下载任务
 */
public class URLHttpDownBootstrapBuilder extends HttpDownBootstrapBuilder {

  private String method;
  private String url;
  private Map<String, String> heads;
  private String body;

  public URLHttpDownBootstrapBuilder(String method, String url, Map<String, String> heads, String body) {
    this.method = method;
    this.url = url;
    this.heads = heads;
    this.body = body;
  }

  public URLHttpDownBootstrapBuilder(String url, Map<String, String> heads, String body) {
    this(null, url, heads, body);
  }

  public URLHttpDownBootstrapBuilder method(String method) {
    this.method = method;
    return this;
  }

  public URLHttpDownBootstrapBuilder url(String url) {
    this.url = url;
    return this;
  }

  public URLHttpDownBootstrapBuilder heads(Map<String, String> heads) {
    this.heads = heads;
    return this;
  }

  public URLHttpDownBootstrapBuilder body(String body) {
    this.body = body;
    return this;
  }

  @Override
  public HttpDownBootstrap build() {
    try {
      HttpRequestInfo request = HttpDownUtil.buildRequest(method, url, heads, body);
      request(request);
      if (getLoopGroup() == null) {
        loopGroup(new NioEventLoopGroup(1));
      }
      HttpResponseInfo response = HttpDownUtil.getHttpResponseInfo(request, null, getProxyConfig(), getLoopGroup());
      if (getResponse() == null) {
        response(response);
      } else {
        if (StringUtil.isNullOrEmpty(getResponse().getFileName())) {
          getResponse().setFileName(response.getFileName());
        }
        getResponse().setSupportRange(response.isSupportRange());
        getResponse().setTotalSize(response.getTotalSize());
      }
    } catch (Exception e) {
      if (getLoopGroup() != null) {
        getLoopGroup().shutdownGracefully();
      }
      throw new BootstrapBuildException("build URLHttpDownBootstrap error", e);
    }
    return super.build();
  }
}
