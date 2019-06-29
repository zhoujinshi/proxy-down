package com.cnbbx.proxy;

import com.cnbbx.proxy.server.HttpProxyServer;
import com.cnbbx.proxy.server.HttpProxyServerConfig;

public class HandelSslHttpProxyServer {

  public static void main(String[] args) throws Exception {
    HttpProxyServerConfig config =  new HttpProxyServerConfig();
    config.setHandleSsl(true);
    new HttpProxyServer()
        .serverConfig(config)
        .start(9999);
  }
}
