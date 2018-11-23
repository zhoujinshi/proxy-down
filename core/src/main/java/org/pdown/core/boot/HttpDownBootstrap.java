package org.pdown.core.boot;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.StringUtil;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.pdown.core.constant.HttpDownStatus;
import org.pdown.core.dispatch.HttpDownCallback;
import org.pdown.core.entity.ChunkInfo;
import org.pdown.core.entity.ConnectInfo;
import org.pdown.core.entity.HttpDownConfigInfo;
import org.pdown.core.entity.HttpRequestInfo;
import org.pdown.core.entity.HttpResponseInfo;
import org.pdown.core.entity.TaskInfo;
import org.pdown.core.exception.BootstrapCreateDirException;
import org.pdown.core.exception.BootstrapException;
import org.pdown.core.exception.BootstrapFileAlreadyExistsException;
import org.pdown.core.exception.BootstrapNoPermissionException;
import org.pdown.core.exception.BootstrapNoSpaceException;
import org.pdown.core.exception.BootstrapPathEmptyException;
import org.pdown.core.handle.DownTimeoutHandler;
import org.pdown.core.proxy.ProxyConfig;
import org.pdown.core.proxy.ProxyHandleFactory;
import org.pdown.core.util.FileUtil;
import org.pdown.core.util.HttpDownUtil;
import org.pdown.core.util.OsUtil;
import org.pdown.core.util.ProtoUtil.RequestProto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpDownBootstrap implements Serializable {

  private static final long serialVersionUID = 7265797940598817922L;
  private static final Logger LOGGER = LoggerFactory.getLogger(HttpDownBootstrap.class);

  private static final long SIZE_1MB = 1024 * 1024L;
  private static final double TIME_1S_NANO = (double) TimeUnit.SECONDS.toNanos(1);

  private HttpRequestInfo request;
  private HttpResponseInfo response;
  private HttpDownConfigInfo downConfig;
  private ProxyConfig proxyConfig;
  private TaskInfo taskInfo;
  private transient HttpDownCallback callback;
  private transient volatile NioEventLoopGroup loopGroup;
  private transient volatile ProgressThread progressThread;

  private HttpDownBootstrap() {
  }

  public HttpDownBootstrap(HttpRequestInfo request, HttpResponseInfo response, HttpDownConfigInfo downConfig, ProxyConfig proxyConfig, TaskInfo taskInfo,
      HttpDownCallback callback, NioEventLoopGroup loopGroup) {
    this.request = request;
    this.response = response;
    this.downConfig = downConfig;
    this.proxyConfig = proxyConfig;
    this.taskInfo = taskInfo;
    this.callback = callback;
    this.loopGroup = loopGroup;
  }

  public HttpRequestInfo getRequest() {
    return request;
  }

  public HttpResponseInfo getResponse() {
    return response;
  }

  public HttpDownConfigInfo getDownConfig() {
    return downConfig;
  }

  public ProxyConfig getProxyConfig() {
    return proxyConfig;
  }

  public TaskInfo getTaskInfo() {
    return taskInfo;
  }

  public HttpDownCallback getCallback() {
    return callback;
  }

  public NioEventLoopGroup getLoopGroup() {
    return loopGroup;
  }

  public HttpDownBootstrap setRequest(HttpRequestInfo request) {
    this.request = request;
    return this;
  }

  public HttpDownBootstrap setResponse(HttpResponseInfo response) {
    this.response = response;
    return this;
  }

  public HttpDownBootstrap setDownConfig(HttpDownConfigInfo downConfig) {
    this.downConfig = downConfig;
    return this;
  }

  public HttpDownBootstrap setProxyConfig(ProxyConfig proxyConfig) {
    this.proxyConfig = proxyConfig;
    return this;
  }

  public HttpDownBootstrap setTaskInfo(TaskInfo taskInfo) {
    this.taskInfo = taskInfo;
    return this;
  }

  public HttpDownBootstrap setCallback(HttpDownCallback callback) {
    this.callback = callback;
    return this;
  }

  public HttpDownBootstrap setLoopGroup(NioEventLoopGroup loopGroup) {
    this.loopGroup = loopGroup;
    return this;
  }

  /**
   * 任务重新开始下载
   */
  private void start(boolean isResume) {
    long startTime = 0;
    long lastPauseTime = 0;
    if (taskInfo != null && taskInfo.getStatus() == HttpDownStatus.WAIT) {
      startTime = taskInfo.getStartTime();
      lastPauseTime = System.currentTimeMillis();
    }
    taskInfo = new TaskInfo();
    taskInfo.setStartTime(startTime);
    taskInfo.setLastPauseTime(lastPauseTime);
    if (downConfig.getFilePath() == null || "".equals(downConfig.getFilePath().trim())) {
      throw new BootstrapPathEmptyException("下载路径不能为空");
    }
    String filePath = HttpDownUtil.getTaskFilePath(this);
    try {
      if (!FileUtil.exists(downConfig.getFilePath())) {
        try {
          Files.createDirectories(Paths.get(downConfig.getFilePath()));
        } catch (IOException e) {
          throw new BootstrapCreateDirException("创建目录失败，请重试", e);
        }
      }
      if (!FileUtil.canWrite(downConfig.getFilePath())) {
        throw new BootstrapNoPermissionException("无权访问下载路径，请修改路径或开放目录写入权限");
      }
      //磁盘空间不足
      if (response.getTotalSize() > FileUtil.getDiskFreeSize(downConfig.getFilePath())) {
        throw new BootstrapNoSpaceException("磁盘空间不足，请修改路径");
      }
      //有文件同名
      File downFile = new File(filePath);
      if (downFile.exists()) {
        //没有进度记录继续下载时，如果存在相同文件则删除重新下载
        if (isResume) {
          downFile.delete();
        } else if (downConfig.isAutoRename()) {
          response.setFileName(FileUtil.renameIfExists(filePath));
          filePath = HttpDownUtil.getTaskFilePath(this);
        } else {
          throw new BootstrapFileAlreadyExistsException("文件名已存在，请修改文件名");
        }
      }
    } catch (BootstrapException e) {
      if (loopGroup != null) {
        loopGroup.shutdownGracefully();
        loopGroup = null;
      }
      if (progressThread != null) {
        progressThread.close();
        progressThread = null;
      }
      throw e;
    }
    try {
      //创建文件
      if (response.isSupportRange()) {
        String fileSystemType = FileUtil.getSystemFileType(filePath);
        if (OsUtil.isUnix()
            || "NTFS".equalsIgnoreCase(fileSystemType)
            || "UFS".equalsIgnoreCase(fileSystemType)
            || "APFS".equalsIgnoreCase(fileSystemType)) {
          FileUtil.createFileWithSparse(filePath, response.getTotalSize());
        } else {
          FileUtil.createFileWithDefault(filePath, response.getTotalSize());
        }
      } else {
        FileUtil.createFile(filePath);
      }
    } catch (IOException e) {
      throw new BootstrapException("创建文件失败，请重试", e);
    }
    buildChunkInfoList();
    commonStart();
    if (taskInfo.getStartTime() <= 0) {
      taskInfo.setStartTime(System.currentTimeMillis());
    }
    //文件下载开始回调
    if (callback != null) {
      request.headers().remove(HttpHeaderNames.RANGE);
      callback.onStart(this);
    }
    for (int i = 0; i < taskInfo.getConnectInfoList().size(); i++) {
      ConnectInfo connectInfo = taskInfo.getConnectInfoList().get(i);
      connect(connectInfo);
    }
  }

  public void start() {
    start(false);
  }

  private void connect(ConnectInfo connectInfo) {
    RequestProto requestProto = request.requestProto();
    LOGGER.debug("开始下载：" + connectInfo);
    Bootstrap bootstrap = new Bootstrap()
        .channel(NioSocketChannel.class)
        .group(loopGroup)
        .handler(new HttpDownInitializer(requestProto.getSsl(), connectInfo));
    if (proxyConfig != null) {
      //代理服务器解析DNS和连接
      bootstrap.resolver(NoopAddressResolverGroup.INSTANCE);
    }
    ChannelFuture cf = bootstrap.connect(requestProto.getHost(), requestProto.getPort());
    //重置最后下载时间
    connectInfo.setConnectChannel(cf.channel());
    cf.addListener((ChannelFutureListener) future -> {
      if (future.isSuccess()) {
        LOGGER.debug("连接成功：" + connectInfo);
        if (response.isSupportRange()) {
          request.headers().set(HttpHeaderNames.RANGE, "bytes=" + connectInfo.getStartPosition() + "-" + connectInfo.getEndPosition());
        } else {
          request.headers().remove(HttpHeaderNames.RANGE);
        }
        future.channel().writeAndFlush(request);
        if (request.content() != null) {
          //请求体写入
          HttpContent content = new DefaultLastHttpContent();
          content.content().writeBytes(request.content());
          future.channel().writeAndFlush(content);
        }
      } else {
        future.channel().close();
      }
    });
  }

  /**
   * 重新发起连接
   */
  private void reConnect(ConnectInfo connectInfo) {
    reConnect(connectInfo, false);
  }

  /**
   * 重新发起连接
   *
   * @param connectInfo 连接相关信息
   * @param isHelp 是否为帮助其他分段下载发起的连接
   */
  private void reConnect(ConnectInfo connectInfo, boolean isHelp) {
    if (loopGroup == null) {
      return;
    }
    if (!isHelp && response.isSupportRange()) {
      connectInfo.setStartPosition(connectInfo.getStartPosition() + connectInfo.getDownSize());
    }
    connectInfo.setDownSize(0);
    if (connectInfo.getErrorCount() < downConfig.getRetryCount()) {
      connect(connectInfo);
    } else {
      if (callback != null) {
        callback.onChunkError(this, taskInfo.getChunkInfoList().get(connectInfo.getChunkIndex()));
      }
      if (taskInfo.getConnectInfoList().stream()
          .filter(connect -> connect.getStatus() != HttpDownStatus.DONE)
          .allMatch(connect -> connect.getErrorCount() >= downConfig.getRetryCount())) {
        taskInfo.setStatus(HttpDownStatus.ERROR);
        taskInfo.getChunkInfoList().stream()
            .filter(chunk -> chunk.getStatus() != HttpDownStatus.DONE)
            .forEach(chunkInfo -> chunkInfo.setStatus(HttpDownStatus.ERROR));
        close();
        if (callback != null) {
          callback.onError(this);
        }
      }
    }
  }

  /**
   * 暂停下载
   */
  public void pause() {
    synchronized (taskInfo) {
      if (taskInfo.getStatus() == HttpDownStatus.PAUSE
          || taskInfo.getStatus() == HttpDownStatus.DONE) {
        return;
      }
      long time = System.currentTimeMillis();
      taskInfo.setStatus(HttpDownStatus.PAUSE);
      taskInfo.setLastPauseTime(time);
      for (ChunkInfo chunkInfo : taskInfo.getChunkInfoList()) {
        if (chunkInfo.getStatus() != HttpDownStatus.DONE) {
          chunkInfo.setStatus(HttpDownStatus.PAUSE);
          chunkInfo.setLastPauseTime(time);
        }
      }
      close();
    }
    if (callback != null) {
      callback.onPause(this);
    }
  }

  /**
   * 继续下载
   */
  public void resume() {
    if (taskInfo == null || taskInfo.getChunkInfoList() == null) {
      //下载进度信息不存在
      start(true);
      return;
    }
    synchronized (taskInfo) {
      if (taskInfo.getStatus() == HttpDownStatus.RUNNING
          || taskInfo.getStatus() == HttpDownStatus.DONE) {
        return;
      }
      if (!FileUtil.exists(HttpDownUtil.getTaskFilePath(this))) {
        close();
        start();
      } else {
        if (taskInfo.getDownSize() >= response.getTotalSize()) {
          taskInfo.setStatus(HttpDownStatus.DONE);
          return;
        }
        long time = System.currentTimeMillis();
        for (ChunkInfo chunkInfo : taskInfo.getChunkInfoList()) {
          if (chunkInfo.getStatus() == HttpDownStatus.PAUSE) {
            chunkInfo.setPauseTime(chunkInfo.getPauseTime() + (time - chunkInfo.getLastPauseTime()));
          }
        }
        commonStart();
        for (ConnectInfo connectInfo : taskInfo.getConnectInfoList()) {
          if (connectInfo.getStatus() == HttpDownStatus.RUNNING) {
            reConnect(connectInfo);
          }
        }
      }
    }
    if (callback != null) {
      callback.onResume(this);
    }
  }

  private void done() {
    stopThreads();
    if (callback != null) {
      callback.onProgress(this);
      callback.onDone(this);
    }
  }

  public void close() {
    synchronized (taskInfo) {
      if (taskInfo.getConnectInfoList() != null) {
        for (ConnectInfo connectInfo : taskInfo.getConnectInfoList()) {
          close(connectInfo);
        }
      }
    }
    stopThreads();
  }

  private void close(ConnectInfo connectInfo) {
    try {
      HttpDownUtil.safeClose(connectInfo.getConnectChannel(), connectInfo.getFileChannel());
    } catch (Exception e) {
      LOGGER.error("closeChunk error", e);
    }
  }

  private void stopThreads() {
    if (loopGroup != null) {
      loopGroup.shutdownGracefully();
      loopGroup = null;
    }
    if (progressThread != null) {
      progressThread.close();
      progressThread = null;
    }
  }

  private void commonStart() {
    taskInfo.setStatus(HttpDownStatus.RUNNING);
    taskInfo.setLastStartTime(0);
    taskInfo.setLastDownSize(taskInfo.getDownSize());
    taskInfo.getChunkInfoList().forEach(chunkInfo -> {
      if (chunkInfo.getStatus() != HttpDownStatus.DONE) {
        chunkInfo.setStatus(HttpDownStatus.RUNNING);
      }
    });
    taskInfo.getConnectInfoList().forEach(connectInfo -> {
      connectInfo.setErrorCount(0);
      if (connectInfo.getStatus() != HttpDownStatus.DONE) {
        connectInfo.setStatus(HttpDownStatus.RUNNING);
      }
    });
    if (loopGroup == null) {
      loopGroup = new NioEventLoopGroup(1);
    }
    if (progressThread == null) {
      progressThread = new ProgressThread();
      progressThread.start();
    }
  }

  /**
   * 生成下载分段信息
   */
  public void buildChunkInfoList() {
    List<ChunkInfo> chunkInfoList = new ArrayList<>();
    List<ConnectInfo> connectInfoList = new ArrayList<>();
    if (response.getTotalSize() > 0) {  //非chunked编码
      //如果文件太小，连接数过高，则调整连接数量
      if (response.getTotalSize() < downConfig.getConnections()) {
        downConfig.setConnections((int) response.getTotalSize());
      }
      //计算chunk列表
      long chunkSize = response.getTotalSize() / downConfig.getConnections();
      for (int i = 0; i < downConfig.getConnections(); i++) {
        ChunkInfo chunkInfo = new ChunkInfo();
        ConnectInfo connectInfo = new ConnectInfo();
        chunkInfo.setIndex(i);
        long start = i * chunkSize;
        if (i == downConfig.getConnections() - 1) { //最后一个连接去下载多出来的字节
          chunkSize += response.getTotalSize() % downConfig.getConnections();
        }
        long end = start + chunkSize - 1;
        chunkInfo.setTotalSize(chunkSize);
        if (taskInfo.getLastPauseTime() > 0) {
          chunkInfo.setLastPauseTime(taskInfo.getLastPauseTime());
          chunkInfo.setPauseTime(taskInfo.getLastPauseTime() - taskInfo.getStartTime());
        }
        chunkInfoList.add(chunkInfo);

        connectInfo.setChunkIndex(i);
        connectInfo.setStartPosition(start);
        connectInfo.setEndPosition(end);
        connectInfoList.add(connectInfo);
      }
    } else { //chunked下载
      ChunkInfo chunkInfo = new ChunkInfo();
      ConnectInfo connectInfo = new ConnectInfo();
      connectInfo.setChunkIndex(0);
      chunkInfo.setIndex(0);
      if (taskInfo.getLastPauseTime() > 0) {
        chunkInfo.setLastPauseTime(taskInfo.getLastPauseTime());
        chunkInfo.setPauseTime(taskInfo.getLastPauseTime() - taskInfo.getStartTime());
      }
      chunkInfoList.add(chunkInfo);
      connectInfoList.add(connectInfo);
    }
    taskInfo.setChunkInfoList(chunkInfoList);
    taskInfo.setConnectInfoList(connectInfoList);
  }

  @Override
  public String toString() {
    return "HttpDownBootstrap{" +
        "request=" + request +
        ", downConfig=" + downConfig +
        ", proxyConfig=" + proxyConfig +
        ", taskInfo=" + taskInfo +
        ", callback=" + (callback != null ? callback.getClass() : callback) +
        '}';
  }

  public static HttpDownBootstrapBuilder builder() {
    return new HttpDownBootstrapBuilder();
  }

  public static URLHttpDownBootstrapBuilder builder(String method, String url, Map<String, String> heads, String body) {
    return new URLHttpDownBootstrapBuilder(method, url, heads, body);
  }

  public static URLHttpDownBootstrapBuilder builder(String url, Map<String, String> heads, String body) {
    return new URLHttpDownBootstrapBuilder(url, heads, body);
  }

  public static URLHttpDownBootstrapBuilder builder(String url, Map<String, String> heads) {
    return builder(url, heads, null);
  }

  public static URLHttpDownBootstrapBuilder builder(String url, String body) {
    return builder(url, null, body);
  }

  public static URLHttpDownBootstrapBuilder builder(String url) {
    return builder(url, null, null);
  }

  class ProgressThread extends Thread {

    private volatile boolean run = true;
    private int period = 1;

    @Override
    public void run() {
      while (run) {
        if (taskInfo.getStatus() != HttpDownStatus.DONE) {
          for (ChunkInfo chunkInfo : taskInfo.getChunkInfoList()) {
            synchronized (chunkInfo) {
              if (chunkInfo.getStatus() != HttpDownStatus.DONE) {
                chunkInfo.setSpeed((chunkInfo.getDownSize() - chunkInfo.getLastCountSize()) / period);
                chunkInfo.setLastCountSize(chunkInfo.getDownSize());
              }
            }
          }
          //计算瞬时速度
          taskInfo.setSpeed(taskInfo.getChunkInfoList()
              .stream()
              .filter(chunkInfo -> chunkInfo.getStatus() != HttpDownStatus.DONE)
              .mapToLong(ChunkInfo::getSpeed)
              .sum());
          callback.onProgress(HttpDownBootstrap.this);
        }
        try {
          TimeUnit.SECONDS.sleep(period);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    }

    public void close() {
      run = false;
    }

  }

  class HttpDownInitializer extends ChannelInitializer {

    private boolean isSsl;
    private ConnectInfo connectInfo;

    public HttpDownInitializer(boolean isSsl, ConnectInfo connectInfo) {
      this.isSsl = isSsl;
      this.connectInfo = connectInfo;
    }

    @Override
    protected void initChannel(Channel ch) throws Exception {
      if (proxyConfig != null) {
        ch.pipeline().addLast(ProxyHandleFactory.build(proxyConfig));
      }
      if (isSsl) {
        RequestProto requestProto = request.requestProto();
        ch.pipeline().addLast(HttpDownUtil.getSslContext().newHandler(ch.alloc(), requestProto.getHost(), requestProto.getPort()));
      }
      ch.pipeline().addLast("downTimeout", new DownTimeoutHandler(downConfig.getTimeout()));
      ch.pipeline().addLast("httpCodec", new HttpClientCodec());
      ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {

        private ChunkInfo chunkInfo = taskInfo.getChunkInfoList().get(connectInfo.getChunkIndex());
        private SeekableByteChannel fileChannel;
        private boolean normalClose;
        private boolean isSuccess;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
          try {
            if (msg instanceof HttpContent) {
              synchronized (taskInfo) {
                if (!isSuccess || !fileChannel.isOpen() || !ctx.channel().isOpen()) {
                  return;
                }
                HttpContent httpContent = (HttpContent) msg;
                ByteBuf byteBuf = httpContent.content();
                int size = byteBuf.readableBytes();
                //判断是否超出下载字节范围
                if (response.isSupportRange() && connectInfo.getDownSize() + size > connectInfo.getTotalSize()) {
                  size = (int) (connectInfo.getTotalSize() - connectInfo.getDownSize());
                }
                fileChannel.write(byteBuf.nioBuffer());
                synchronized (chunkInfo) {
                  chunkInfo.setDownSize(chunkInfo.getDownSize() + size);
                  taskInfo.setDownSize(taskInfo.getDownSize() + size);
                  connectInfo.setDownSize(connectInfo.getDownSize() + size);
                }
                if (downConfig.getSpeedLimit() > 0) {
                  long time = System.nanoTime();
                  if (taskInfo.getLastStartTime() <= 0) {
                    taskInfo.setLastStartTime(time);
                  } else {
                    //计算下载速度是否超过速度限制
                    long useTime = time - taskInfo.getLastStartTime();
                    double speed = (taskInfo.getDownSize() - taskInfo.getLastDownSize()) / (double) useTime;
                    double limitSpeed = downConfig.getSpeedLimit() / TIME_1S_NANO;
                    long sleepTime = (long) ((speed - limitSpeed) * useTime);
                    if (sleepTime > 0) {
                      TimeUnit.NANOSECONDS.sleep(sleepTime);
                      //刷新其他连接的lastReadTime
                      for (ConnectInfo ci : taskInfo.getConnectInfoList()) {
                        if (ci.getConnectChannel() != null && ci.getConnectChannel() != ch) {
                          DownTimeoutHandler downTimeout = (DownTimeoutHandler) ci.getConnectChannel().pipeline().get("downTimeout");
                          if (downTimeout != null) {
                            downTimeout.updateLastReadTime(sleepTime);
                          }
                        }
                      }
                    }
                  }
                }
                if (!response.isSupportRange() && !(httpContent instanceof LastHttpContent)) {
                  return;
                }
                long time = System.currentTimeMillis();
                if (connectInfo.getDownSize() >= connectInfo.getTotalSize()) {
                  LOGGER.debug("连接下载完成：" + connectInfo + "\t" + chunkInfo);
                  normalClose = true;
                  close(connectInfo);
                  //判断分段是否下载完成
                  if (isChunkDownDone(httpContent)) {
                    LOGGER.debug("分段下载完成：" + connectInfo + "\t" + chunkInfo);
                    if (!response.isSupportRange()) {  //chunked编码最后更新文件大小
                      chunkInfo.setTotalSize(taskInfo.getDownSize());
                      response.setTotalSize(taskInfo.getDownSize());
                    }
                    synchronized (chunkInfo) {
                      chunkInfo.setStatus(HttpDownStatus.DONE);
                      //计算最后平均下载速度
                      chunkInfo.setSpeed(calcSpeed(chunkInfo.getTotalSize(), time - taskInfo.getStartTime() - chunkInfo.getPauseTime()));
                    }
                    //分段下载完成回调
                    if (callback != null) {
                      callback.onChunkDone(HttpDownBootstrap.this, chunkInfo);
                    }
                    //所有分段都下载完成
                    if (taskInfo.getChunkInfoList().stream().allMatch(chunk -> chunk.getStatus() == HttpDownStatus.DONE)) {
                      //文件下载完成回调
                      LOGGER.debug("任务下载完成：" + chunkInfo);
                      connectInfo.setStatus(HttpDownStatus.DONE);
                      taskInfo.setStatus(HttpDownStatus.DONE);
                      //计算平均速度(所有分段的平均速度之和)
                      taskInfo.setSpeed(taskInfo.getChunkInfoList()
                          .stream()
                          .mapToLong(chunk -> chunk.getSpeed())
                          .sum());
                      done();
                      return;
                    }
                  }
                  //判断是否要去支持其他分段,找一个下载最慢的分段
                  ChunkInfo supportChunk = taskInfo.getChunkInfoList()
                      .stream()
                      .filter(chunk -> chunk.getIndex() != connectInfo.getChunkIndex() && chunk.getStatus() != HttpDownStatus.DONE)
                      .min(Comparator.comparingLong(ChunkInfo::getDownSize))
                      .orElse(null);
                  if (supportChunk == null) {
                    connectInfo.setStatus(HttpDownStatus.DONE);
                    return;
                  }
                  ConnectInfo maxConnect = taskInfo.getConnectInfoList()
                      .stream()
                      .filter(connect -> connect.getChunkIndex() == supportChunk.getIndex() && connect.getTotalSize() - connect.getDownSize() >= SIZE_1MB)
                      .max((c1, c2) -> (int) (c1.getStartPosition() - c2.getStartPosition()))
                      .orElse(null);
                  if (maxConnect == null) {
                    connectInfo.setStatus(HttpDownStatus.DONE);
                    return;
                  }
                  //把这个分段最后一个下载连接分成两个
                  long remainingSize = maxConnect.getTotalSize() - maxConnect.getDownSize();
                  long endTemp = maxConnect.getEndPosition();
                  maxConnect.setEndPosition(maxConnect.getEndPosition() - (remainingSize / 2));
                  //给当前连接重新分配下载区间
                  connectInfo.setStartPosition(maxConnect.getEndPosition() + 1);
                  connectInfo.setEndPosition(endTemp);
                  connectInfo.setChunkIndex(supportChunk.getIndex());
                  LOGGER.debug("支持下载：" + connectInfo + "\t" + chunkInfo);
                  reConnect(connectInfo, true);
                }
              }
            } else {
              HttpResponse httpResponse = (HttpResponse) msg;
              Integer responseCode = httpResponse.status().code();
              if (responseCode < 200 || responseCode >= 300) {
                LOGGER.warn("响应状态码异常：" + responseCode + "\t" + connectInfo);
                //处理重定向链接
                if (responseCode >= 300 && responseCode < 400) {
                  String location = httpResponse.headers().get(HttpHeaderNames.LOCATION);
                  if (!StringUtil.isNullOrEmpty(location)) {
                    if (location.matches("^(?i)(https?).*")) {
                      URL url = new URL(location);
                      request.setUri(url.getFile());
                      request.headers().set(HttpHeaderNames.HOST, url.getHost());
                      request.setRequestProto(HttpDownUtil.parseRequestProto(url));
                    } else {
                      request.setUri(location);
                    }
                  }
                } else {
                  connectInfo.setErrorCount(connectInfo.getErrorCount() + 1);
                }
                normalClose = true;
                close(connectInfo);
                ch.eventLoop().schedule(() -> reConnect(connectInfo), 5, TimeUnit.SECONDS);
                return;
              }
              LOGGER.debug("下载响应：" + connectInfo);
              fileChannel = Files.newByteChannel(Paths.get(HttpDownUtil.getTaskFilePath(HttpDownBootstrap.this)), StandardOpenOption.WRITE);
              if (response.isSupportRange()) {
                fileChannel.position(connectInfo.getStartPosition() + connectInfo.getDownSize());
              }
              connectInfo.setFileChannel(fileChannel);
              isSuccess = true;
            }
          } catch (Exception e) {
            throw e;
          } finally {
            ReferenceCountUtil.release(msg);
          }
        }

        private boolean isChunkDownDone(HttpContent httpContent) {
          if (response.isSupportRange()) {
            if (chunkInfo.getDownSize() >= chunkInfo.getTotalSize()) {
              return true;
            }
          } else if (httpContent instanceof LastHttpContent) {
            return true;
          }
          return false;
        }

        /**
         * 计算下载速度(B/S)
         * @param downSize  下载的字节数
         * @param downTime  下载耗时
         * @return
         */
        private long calcSpeed(long downSize, long downTime) {
          return (long) (downSize / (downTime / 1000D));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
          if (!(cause instanceof IOException) && !(cause instanceof ReadTimeoutException)) {
            LOGGER.error("down onChunkError:", cause);
          }
          normalClose = true;
          close(connectInfo);
          reConnect(connectInfo);
        }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
          super.channelUnregistered(ctx);
          //还未下载完成，继续下载
          if (!normalClose
              && chunkInfo.getStatus() != HttpDownStatus.PAUSE
              && chunkInfo.getStatus() != HttpDownStatus.ERROR) {
            LOGGER.debug("channelUnregistered:" + connectInfo + "\t" + chunkInfo);
            close(connectInfo);
            reConnect(connectInfo);
          }
        }
      });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      super.exceptionCaught(ctx, cause);
      LOGGER.error("down onInit:", cause);
    }
  }
}
