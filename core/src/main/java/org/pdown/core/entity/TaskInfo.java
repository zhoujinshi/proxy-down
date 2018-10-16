package org.pdown.core.entity;

import java.io.Serializable;
import java.util.List;

public class TaskInfo implements Serializable {

  private static final long serialVersionUID = 4813413517396555930L;
  private long downSize;
  private long startTime;
  private transient long lastStartTime;
  private transient long lastDownSize;
  private long lastPauseTime;
  private int status;
  private long speed;
  private List<ChunkInfo> chunkInfoList;
  private List<ConnectInfo> connectInfoList;

  public TaskInfo setDownSize(long downSize) {
    this.downSize = downSize;
    return this;
  }

  public long getStartTime() {
    return startTime;
  }

  public TaskInfo setStartTime(long startTime) {
    this.startTime = startTime;
    return this;
  }

  public long getLastStartTime() {
    return lastStartTime;
  }

  public TaskInfo setLastStartTime(long lastStartTime) {
    this.lastStartTime = lastStartTime;
    return this;
  }

  public long getLastDownSize() {
    return lastDownSize;
  }

  public TaskInfo setLastDownSize(long lastDownSize) {
    this.lastDownSize = lastDownSize;
    return this;
  }

  public long getLastPauseTime() {
    return lastPauseTime;
  }

  public TaskInfo setLastPauseTime(long lastPauseTime) {
    this.lastPauseTime = lastPauseTime;
    return this;
  }

  public int getStatus() {
    return status;
  }

  public TaskInfo setStatus(int status) {
    this.status = status;
    return this;
  }

  public long getSpeed() {
    return speed;
  }

  public TaskInfo setSpeed(long speed) {
    this.speed = speed;
    return this;
  }

  public List<ChunkInfo> getChunkInfoList() {
    return chunkInfoList;
  }

  public TaskInfo setChunkInfoList(List<ChunkInfo> chunkInfoList) {
    this.chunkInfoList = chunkInfoList;
    return this;
  }

  public List<ConnectInfo> getConnectInfoList() {
    return connectInfoList;
  }

  public TaskInfo setConnectInfoList(List<ConnectInfo> connectInfoList) {
    this.connectInfoList = connectInfoList;
    return this;
  }

  public long getDownSize() {
    return downSize;
  }

  @Override
  public String toString() {
    return "TaskInfo{" +
        "downSize=" + downSize +
        ", startTime=" + startTime +
        ", lastStartTime=" + lastStartTime +
        ", status=" + status +
        ", speed=" + speed +
        ", chunkInfoList=" + chunkInfoList +
        ", connectInfoList=" + connectInfoList +
        '}';
  }
}
