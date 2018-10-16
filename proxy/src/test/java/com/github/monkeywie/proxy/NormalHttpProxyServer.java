package com.github.zhoujinshi.proxy;

import com.github.zhoujinshi.proxy.server.HttpProxyServer;
import com.github.zhoujinshi.proxy.server.HttpProxyServerConfig;

public class NormalHttpProxyServer {

  public static void main(String[] args) throws Exception {
   //new HttpProxyServer().start(9998);

    HttpProxyServerConfig config =  new HttpProxyServerConfig();
    config.setBossGroupThreads(1);
    config.setWorkerGroupThreads(1);
    config.setProxyGroupThreads(1);
    new HttpProxyServer()
        .serverConfig(config)
        .start(9999);
  }
}
