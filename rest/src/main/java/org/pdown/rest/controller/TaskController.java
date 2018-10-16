package org.pdown.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.util.internal.StringUtil;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import org.pdown.core.boot.HttpDownBootstrap;
import org.pdown.core.boot.HttpDownBootstrapBuilder;
import org.pdown.core.constant.HttpDownStatus;
import org.pdown.core.entity.HttpDownConfigInfo;
import org.pdown.core.entity.HttpRequestInfo;
import org.pdown.core.entity.TaskInfo;
import org.pdown.core.exception.BootstrapCreateDirException;
import org.pdown.core.exception.BootstrapException;
import org.pdown.core.exception.BootstrapFileAlreadyExistsException;
import org.pdown.core.exception.BootstrapNoPermissionException;
import org.pdown.core.exception.BootstrapNoSpaceException;
import org.pdown.core.exception.BootstrapPathEmptyException;
import org.pdown.core.util.FileUtil;
import org.pdown.core.util.HttpDownUtil;
import org.pdown.rest.base.exception.NotFoundException;
import org.pdown.rest.base.exception.ParameterException;
import org.pdown.rest.content.ConfigContent;
import org.pdown.rest.content.HttpDownContent;
import org.pdown.rest.entity.HttpResult;
import org.pdown.rest.entity.ServerConfigInfo;
import org.pdown.rest.form.CreateTaskForm;
import org.pdown.rest.form.EventForm;
import org.pdown.rest.form.HttpRequestForm;
import org.pdown.rest.form.TaskForm;
import org.pdown.rest.util.ContentUtil;
import org.pdown.rest.vo.ResumeVo;
import org.pdown.rest.websocket.TaskEvent;
import org.pdown.rest.websocket.TaskEventHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TaskController {

  /*
  Create a download task
   */
  @PostMapping("tasks")
  public ResponseEntity create(HttpServletRequest request, @RequestParam(name = "refresh", required = false) boolean refresh) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    CreateTaskForm createTaskForm = mapper.readValue(request.getInputStream(), CreateTaskForm.class);
    if (createTaskForm.getRequest() == null) {
      throw new ParameterException(4001, "request can't be empty");
    }
    if (StringUtils.isEmpty(createTaskForm.getRequest().getUrl())) {
      throw new ParameterException(4002, "URL can't be empty");
    }
    HttpRequestForm requestForm = createTaskForm.getRequest();
    HttpDownBootstrapBuilder bootstrapBuilder;
    //If know response Content-Length and file name,can create a task directly, without spending a request to resolve the task name and size.
    if (createTaskForm.getResponse() != null
        && createTaskForm.getResponse().getTotalSize() > 0
        && !StringUtil.isNullOrEmpty(createTaskForm.getResponse().getFileName())) {
      HttpRequestInfo httpRequestInfo = HttpDownUtil.buildRequest(requestForm.getMethod(), requestForm.getUrl(), requestForm.getHeads(), requestForm.getBody());
      bootstrapBuilder = HttpDownBootstrap.builder().request(httpRequestInfo);
    } else {
      bootstrapBuilder = HttpDownBootstrap.builder(requestForm.getMethod(), createTaskForm.getRequest().getUrl(), createTaskForm.getRequest().getHeads(), createTaskForm.getRequest().getBody());
    }
    //build a default taskInfo with WAIT status
    TaskInfo taskInfo = new TaskInfo()
        .setStatus(HttpDownStatus.WAIT)
        .setStartTime(System.currentTimeMillis());
    HttpDownBootstrap httpDownBootstrap = bootstrapBuilder.response(createTaskForm.getResponse())
        .downConfig(buildConfig(createTaskForm.getConfig()))
        .taskInfo(taskInfo)
        .callback(new PersistenceHttpDownCallback())
        .proxyConfig(ConfigContent.getInstance().get().getProxyConfig())
        .build();
    HttpDownContent downContent = HttpDownContent.getInstance();
    String id = UUID.randomUUID().toString();
    synchronized (downContent) {
      long runningCount = downContent.get().values().stream()
          .filter(bootstrap -> bootstrap.getTaskInfo().getStatus() == HttpDownStatus.RUNNING)
          .count();
      if (runningCount < ConfigContent.getInstance().get().getTaskLimit()) {
        try {
          httpDownBootstrap.start();
        } catch (BootstrapException e) {
          if (e instanceof BootstrapPathEmptyException) {
            throw new ParameterException(4003, "Save path is empty");
          } else if (e instanceof BootstrapCreateDirException) {
            throw new ParameterException(4004, "Can't create dir");
          } else if (e instanceof BootstrapNoPermissionException) {
            throw new ParameterException(4005, "No permission");
          } else if (e instanceof BootstrapNoSpaceException) {
            throw new ParameterException(4006, "No space");
          } else if (e instanceof BootstrapFileAlreadyExistsException) {
            if (refresh) {
              //find same task
              Entry<String, HttpDownBootstrap> sameEntry = downContent.get().entrySet().stream()
                  .filter(entry -> {
                    HttpDownBootstrap bootstrap = entry.getValue();
                    Path newTaskPath = Paths.get(createTaskForm.getConfig().getFilePath(), createTaskForm.getResponse().getFileName());
                    Path oldTaskPath = Paths.get(bootstrap.getDownConfig().getFilePath(), bootstrap.getResponse().getFileName());
                    return newTaskPath.equals(oldTaskPath);
                  })
                  .findFirst()
                  .orElse(null);
              //refresh task
              if (sameEntry != null) {
                return refreshCommon(sameEntry.getKey(), createTaskForm.getRequest());
              }
            }
            throw new ParameterException(4007, "File already exists");
          }
        }
      }
    }
    downContent.put(id, httpDownBootstrap).save();
    PersistenceHttpDownCallback.calcSpeedLimit();
    TaskForm taskForm = new TaskForm();
    taskForm.setId(id);
    taskForm.setRequest(HttpRequestForm.parse(httpDownBootstrap.getRequest()));
    taskForm.setConfig(httpDownBootstrap.getDownConfig());
    taskForm.setInfo(httpDownBootstrap.getTaskInfo());
    taskForm.setResponse(httpDownBootstrap.getResponse());
    TaskEventHandler.dispatchEvent(new EventForm(TaskEvent.CREATE, taskForm));
    return ResponseEntity.ok(taskForm);
  }

  @GetMapping("tasks")
  public ResponseEntity list(@RequestParam(required = false, name = "status") String statuses) {
    List<TaskForm> list = HttpDownContent.getInstance().get()
        .entrySet()
        .stream()
        .filter(entry -> {
          if (StringUtils.isEmpty(statuses)) {
            return true;
          } else {
            return Arrays.stream(statuses.split(",")).anyMatch(status -> status.equals(entry.getValue().getTaskInfo().getStatus() + ""));
          }
        })
        .sorted((e1, e2) -> (int) (e2.getValue().getTaskInfo().getStartTime() - e1.getValue().getTaskInfo().getStartTime()))
        .map(entry -> TaskForm.parse(entry.getKey(), entry.getValue()))
        .collect(Collectors.toList());
    return ResponseEntity.ok(list);
  }

  @GetMapping("tasks/{id}")
  public ResponseEntity detail(@PathVariable String id) {
    HttpDownBootstrap bootstrap = HttpDownContent.getInstance().get(id);
    if (bootstrap == null) {
      throw new NotFoundException("task does not exist");
    }
    return ResponseEntity.ok(TaskForm.parse(id, bootstrap));
  }

  @PutMapping("tasks/{id}")
  public ResponseEntity refresh(@PathVariable String id, HttpServletRequest request) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    HttpRequestForm requestForm = mapper.readValue(request.getInputStream(), HttpRequestForm.class);
    return refreshCommon(id, requestForm);
  }

  private ResponseEntity refreshCommon(String id, HttpRequestForm requestForm) throws MalformedURLException {
    HttpDownBootstrap bootstrap = HttpDownContent.getInstance().get(id);
    if (bootstrap == null) {
      throw new NotFoundException("task does not exist");
    }
    boolean pauseFlag = false;
    if (bootstrap.getTaskInfo().getStatus() == HttpDownStatus.RUNNING) {
      bootstrap.pause();
      pauseFlag = true;
    }
    bootstrap.setRequest(HttpDownUtil.buildRequest(requestForm.getMethod(), requestForm.getUrl(), requestForm.getHeads(), requestForm.getBody()));
    HttpDownContent.getInstance().save();
    if (pauseFlag) {
      ResumeVo resumeVo = handleResume(Arrays.asList(id));
      TaskEventHandler.dispatchEvent(new EventForm(TaskEvent.RESUME, resumeVo));
    }
    return ResponseEntity.ok(null);
  }

  @DeleteMapping("tasks/{ids}")
  public ResponseEntity delete(@PathVariable String ids, @RequestParam(required = false) boolean delFile)
      throws IOException {
    String[] idArray = ids.split(",");
    commonDelete(Arrays.asList(idArray), delFile);
    return ResponseEntity.ok(null);
  }

  /*
  ID比较多时HTTP DELETE不支持请求体，url过长会报错
  所以这里提供一个HTTP POST 接口进行删除
   */
  @PostMapping("tasks/delete")
  public ResponseEntity deleteLog(HttpServletRequest request, @RequestParam(required = false) boolean delFile) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    List<String> ids = request.getContentLength() > 0 ?
        mapper.readValue(request.getInputStream(), ArrayList.class) : new ArrayList<>();
    if (ids.size() > 0) {
      commonDelete(ids, delFile);
    }
    return ResponseEntity.ok(null);
  }

  @DeleteMapping("tasks")
  public ResponseEntity delete(@RequestParam(required = false) boolean delFile) throws IOException {
    List<String> ids = HttpDownContent.getInstance().get().keySet().stream().collect(Collectors.toList());
    if (ids.size() > 0) {
      commonDelete(ids, delFile);
    }
    return ResponseEntity.ok(null);
  }


  private void commonDelete(List<String> idArray, boolean delFile) throws IOException {
    HttpDownContent httpDownContent = HttpDownContent.getInstance();
    for (String id : idArray) {
      HttpDownBootstrap bootstrap = HttpDownContent.getInstance().get(id);
      if (bootstrap == null) {
        continue;
      }
      bootstrap.close();
      //Delete download progress record file
      String recordFile = httpDownContent.progressSavePath(bootstrap.getDownConfig(), bootstrap.getResponse());
      FileUtil.deleteIfExists(recordFile);
      FileUtil.deleteIfExists(ContentUtil.buildBakPath(recordFile));
      if (delFile) {
        //Delete download file
        FileUtil.deleteIfExists(HttpDownUtil.getTaskFilePath(bootstrap));
      }
      httpDownContent.remove(id);
    }
    httpDownContent.save();
    TaskEventHandler.dispatchEvent(new EventForm(TaskEvent.DELETE, idArray));
  }

  @PutMapping("tasks/{ids}/pause")
  public ResponseEntity pauseDown(@PathVariable String ids) {
    String[] idArray = ids.split(",");
    for (String id : idArray) {
      HttpDownBootstrap httpDownBootstrap = HttpDownContent.getInstance().get(id);
      if (httpDownBootstrap == null) {
        continue;
      }
      HttpDownContent.getInstance().get(id).pause();
    }
    PersistenceHttpDownCallback.calcSpeedLimit();
    HttpDownContent.getInstance().save();
    TaskEventHandler.dispatchEvent(new EventForm(TaskEvent.PAUSE, idArray));
    return ResponseEntity.ok(null);
  }

  @PutMapping("tasks/pause")
  public ResponseEntity<HttpResult> pauseAll(HttpServletRequest request) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    List<String> ids = request.getContentLength() > 0 ?
        mapper.readValue(request.getInputStream(), ArrayList.class) : new ArrayList<>();
    HttpDownContent.getInstance()
        .get()
        .entrySet()
        .stream()
        .filter(entry -> {
          TaskInfo taskInfo = entry.getValue().getTaskInfo();
          if (taskInfo.getStatus() == HttpDownStatus.RUNNING
              || taskInfo.getStatus() == HttpDownStatus.WAIT) {
            if (ids.size() == 0 || ids.contains(entry.getKey())) {
              return true;
            }
          }
          return false;
        })
        .forEach(entry -> entry.getValue().pause());
    PersistenceHttpDownCallback.calcSpeedLimit();
    HttpDownContent.getInstance().save();
    TaskEventHandler.dispatchEvent(new EventForm(TaskEvent.PAUSE, ids));
    return ResponseEntity.ok(null);
  }

  @PutMapping("tasks/{ids}/resume")
  public ResponseEntity resume(@PathVariable String ids) {
    ResumeVo resumeVo = handleResume(Arrays.asList(ids.split(",")));
    PersistenceHttpDownCallback.calcSpeedLimit();
    HttpDownContent.getInstance().save();
    TaskEventHandler.dispatchEvent(new EventForm(TaskEvent.RESUME, resumeVo));
    return ResponseEntity.ok(resumeVo);
  }

  @PutMapping("tasks/resume")
  public ResponseEntity resumeAll(HttpServletRequest request) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    List<String> ids = request.getContentLength() > 0 ?
        mapper.readValue(request.getInputStream(), ArrayList.class) : new ArrayList<>();
    ResumeVo resumeVo = handleResume(ids);
    PersistenceHttpDownCallback.calcSpeedLimit();
    HttpDownContent.getInstance().save();
    TaskEventHandler.dispatchEvent(new EventForm(TaskEvent.RESUME, resumeVo));
    return ResponseEntity.ok(resumeVo);
  }

  @GetMapping("tasks/progress")
  public ResponseEntity progress(@RequestParam(required = false) String[] ids) {
    if (ids == null || ids.length == 0) {
      throw new NotFoundException("tasks progress does not exist");
    }
    List<TaskForm> list = Arrays.stream(ids)
        .filter(id -> HttpDownContent.getInstance().get(id) != null)
        .map(id -> {
          TaskForm taskForm = new TaskForm();
          HttpDownBootstrap httpDownBootstrap = HttpDownContent.getInstance().get(id);
          taskForm.setId(id);
          taskForm.setInfo(httpDownBootstrap.getTaskInfo());
          taskForm.setResponse(httpDownBootstrap.getResponse());
          return taskForm;
        })
        .collect(Collectors.toList());
    return ResponseEntity.ok(list);
  }

  //Pause running task
  private ResumeVo handleResume(List<String> resumeIds) {
    ResumeVo resumeVo = new ResumeVo();
    List<Entry<String, HttpDownBootstrap>> runList = new ArrayList<>();
    List<Entry<String, HttpDownBootstrap>> waitList = new ArrayList<>();
    for (Entry<String, HttpDownBootstrap> entry : HttpDownContent.getInstance().get().entrySet()) {
      HttpDownBootstrap httpDownBootstrap = entry.getValue();
      int status = httpDownBootstrap.getTaskInfo().getStatus();
      if (status == HttpDownStatus.RUNNING) {
        runList.add(entry);
      } else if (status != HttpDownStatus.DONE) {
        waitList.add(entry);
      }
    }

    List<String> needResumeIds = new ArrayList<>();
    List<String> needPauseIds = new ArrayList<>();
    List<String> needWaitIds = new ArrayList<>();
    int taskLimit = ConfigContent.getInstance().get().getTaskLimit();
    //没有指定继续下载的任务ID，则继续所有的下载任务
    if (resumeIds == null || resumeIds.size() == 0) {
      int needResumeCount = taskLimit - runList.size();
      //计算出要继续下载的任务ID和待下载的任务ID
      for (int i = 0; i < waitList.size(); i++) {
        Entry<String, HttpDownBootstrap> entry = waitList.get(i);
        if (i < needResumeCount) {
          needResumeIds.add(entry.getKey());
        } else {
          needWaitIds.add(entry.getKey());
        }
      }
    } else {
      for (int i = 0, j = 0; i < waitList.size(); i++) {
        Entry<String, HttpDownBootstrap> entry = waitList.get(i);
        if (resumeIds.contains(entry.getKey())) {
          if (j < taskLimit) {
            needResumeIds.add(entry.getKey());
            j++;
          } else {
            needWaitIds.add(entry.getKey());
          }
        }
      }
    }
    resumeVo.setResumeIds(needResumeIds);
    resumeVo.setPauseIds(needPauseIds);
    resumeVo.setWaitIds(needWaitIds);
    if (needResumeIds.size() > 0) {
      //计算需要被暂停的任务个数
      int needPauseCount = runList.size() + needResumeIds.size() - taskLimit;
      if (needPauseCount > 0) {
        //暂停正在运行的任务,状态更新成待下载
        for (int i = 0; i < needPauseCount; i++) {
          Entry<String, HttpDownBootstrap> entry = runList.get(i);
          needPauseIds.add(entry.getKey());
          entry.getValue().pause();
          needWaitIds.add(entry.getKey());
        }
      }
      //开始指定要继续下载的任务
      for (Entry<String, HttpDownBootstrap> entry : waitList) {
        if (needResumeIds.contains(entry.getKey())) {
          entry.getValue().resume();
        }
      }
    }
    if (needWaitIds.size() > 0) {
      waitList.stream()
          .filter(entry -> needWaitIds.contains(entry.getKey()))
          .forEach(entry -> entry.getValue().getTaskInfo().setStatus(HttpDownStatus.WAIT));
    }
    return resumeVo;
  }

  private HttpDownConfigInfo buildConfig(HttpDownConfigInfo configForm) {
    ServerConfigInfo serverConfigInfo = ConfigContent.getInstance().get();
    if (StringUtils.isEmpty(configForm.getFilePath())) {
      configForm.setFilePath(serverConfigInfo.getFilePath());
    }
    if (configForm.getConnections() <= 0) {
      configForm.setConnections(serverConfigInfo.getConnections());
    }
    if (configForm.getTimeout() <= 0) {
      configForm.setTimeout(configForm.getTimeout());
    }
    if (configForm.getRetryCount() <= 0) {
      configForm.setRetryCount(configForm.getRetryCount());
    }
    if (!configForm.isAutoRename()) {
      configForm.setAutoRename(serverConfigInfo.isAutoRename());
    }
    if (configForm.getSpeedLimit() <= 0) {
      configForm.setSpeedLimit(configForm.getSpeedLimit());
    }
    return configForm;
  }
}
