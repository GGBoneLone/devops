/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Code Technology Studio
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.jpom.monitor;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.cron.task.Task;
import cn.hutool.db.sql.Direction;
import cn.hutool.db.sql.Order;
import cn.hutool.http.HttpStatus;
import cn.jiangzeyin.common.JsonMessage;
import cn.jiangzeyin.common.spring.SpringUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.jpom.common.forward.NodeForward;
import io.jpom.common.forward.NodeUrl;
import io.jpom.model.data.MonitorModel;
import io.jpom.model.data.NodeModel;
import io.jpom.model.user.UserModel;
import io.jpom.model.log.MonitorNotifyLog;
import io.jpom.model.node.ProjectInfoCacheModel;
import io.jpom.plugin.IPlugin;
import io.jpom.plugin.PluginFactory;
import io.jpom.service.dblog.DbMonitorNotifyLogService;
import io.jpom.service.monitor.MonitorService;
import io.jpom.service.node.NodeService;
import io.jpom.service.node.ProjectInfoCacheService;
import io.jpom.service.user.UserService;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ???????????????
 *
 * @author bwcx_jzy
 * @since 2021/12/14
 */
@Slf4j
public class MonitorItem implements Task {


    private final DbMonitorNotifyLogService dbMonitorNotifyLogService;
    private final UserService userService;
    private final MonitorService monitorService;
    private final ProjectInfoCacheService projectInfoCacheService;
    private final NodeService nodeService;
    private final String monitorId;
    private MonitorModel monitorModel;

    public MonitorItem(String id) {
        this.dbMonitorNotifyLogService = SpringUtil.getBean(DbMonitorNotifyLogService.class);
        this.userService = SpringUtil.getBean(UserService.class);
        this.monitorService = SpringUtil.getBean(MonitorService.class);
        this.nodeService = SpringUtil.getBean(NodeService.class);
        this.projectInfoCacheService = SpringUtil.getBean(ProjectInfoCacheService.class);
        this.monitorId = id;
    }

    @Override
    public void execute() {
        // ????????????
        this.monitorModel = monitorService.getByKey(monitorId);
        List<MonitorModel.NodeProject> nodeProjects = monitorModel.projects();
        //
        List<Boolean> collect = nodeProjects.stream().map(nodeProject -> {
            String nodeId = nodeProject.getNode();
            NodeModel nodeModel = nodeService.getByKey(nodeId);
            if (nodeModel == null) {
                return true;
            }
            return this.reqNodeStatus(nodeModel, nodeProject.getProjects());
        }).filter(aBoolean -> !aBoolean).collect(Collectors.toList());
        boolean allRun = CollUtil.isEmpty(collect);
        // ????????????
        monitorService.setAlarm(monitorModel.getId(), !allRun);
    }

    /**
     * ???????????????????????????
     *
     * @param nodeModel ??????
     * @param projects  ??????
     * @return true ?????????????????????
     */
    private boolean reqNodeStatus(NodeModel nodeModel, List<String> projects) {
        if (projects == null || projects.isEmpty()) {
            return true;
        }
        List<Boolean> collect = projects.stream().map(id -> {
            //
            String title;
            String context;
            try {
                //????????????????????????
                JsonMessage<JSONObject> jsonMessage = NodeForward.requestBySys(nodeModel, NodeUrl.Manage_GetProjectStatus, "id", id, "getCopy", true);
                if (jsonMessage.getCode() == HttpStatus.HTTP_OK) {
                    JSONObject jsonObject = jsonMessage.getData();
                    int pid = jsonObject.getIntValue("pId");
                    boolean runStatus = this.checkNotify(monitorModel, nodeModel, id, null, pid > 0);
                    // ????????????
                    List<Boolean> booleanList = null;
                    JSONArray copys = jsonObject.getJSONArray("copys");
                    if (CollUtil.isNotEmpty(copys)) {
                        booleanList = copys.stream().map(o -> {
                            JSONObject jsonObject1 = (JSONObject) o;
                            String copyId = jsonObject1.getString("copyId");
                            boolean status = jsonObject1.getBooleanValue("status");
                            return MonitorItem.this.checkNotify(monitorModel, nodeModel, id, copyId, status);
                        }).filter(aBoolean -> !aBoolean).collect(Collectors.toList());
                    }
                    return runStatus && CollUtil.isEmpty(booleanList);
                } else {
                    title = StrUtil.format("???{}??????????????????????????????{}", nodeModel.getName(), jsonMessage.getCode());
                    context = jsonMessage.toString();
                }
            } catch (Exception e) {
                log.error("?????? {} ???????????? {}", nodeModel.getName(), e.getMessage());
                //
                title = StrUtil.format("???{}??????????????????????????????", nodeModel.getName());
                context = ExceptionUtil.stacktraceToString(e);
            }
            // ??????????????????
            boolean pre = this.getPreStatus(monitorModel.getId(), nodeModel.getId(), id);
            if (pre) {
                // ????????????
                MonitorNotifyLog monitorNotifyLog = new MonitorNotifyLog();
                monitorNotifyLog.setStatus(false);
                monitorNotifyLog.setTitle(title);
                monitorNotifyLog.setContent(context);
                monitorNotifyLog.setCreateTime(System.currentTimeMillis());
                monitorNotifyLog.setNodeId(nodeModel.getId());
                monitorNotifyLog.setProjectId(id);
                monitorNotifyLog.setMonitorId(monitorModel.getId());
                //
                this.notifyMsg(nodeModel, monitorNotifyLog);
            }
            return false;
        }).filter(aBoolean -> !aBoolean).collect(Collectors.toList());
        return CollUtil.isEmpty(collect);
    }

    /**
     * ????????????
     *
     * @param monitorModel ????????????
     * @param nodeModel    ????????????
     * @param id           ??????id
     * @param copyId       ??????id
     * @param runStatus    ??????????????????
     */
    private boolean checkNotify(MonitorModel monitorModel, NodeModel nodeModel, String id, String copyId, boolean runStatus) {
        // ??????????????????
        String projectCopyId = id;
        String copyMsg = StrUtil.EMPTY;
        if (StrUtil.isNotEmpty(copyId)) {
            projectCopyId = StrUtil.format("{}:{}", id, copyId);
            copyMsg = StrUtil.format("?????????{}", copyId);
        }
        boolean pre = this.getPreStatus(monitorModel.getId(), nodeModel.getId(), projectCopyId);
        String title = null;
        String context = null;
        //????????????????????????
        if (runStatus) {
            if (!pre) {
                // ?????????????????????
                title = StrUtil.format("???{}???????????????{}?????????{}????????????????????????", nodeModel.getName(), id, copyMsg);
                context = "";
            }
        } else {
            //
            if (monitorModel.autoRestart()) {
                // ????????????
                try {
                    JsonMessage<String> reJson = NodeForward.requestBySys(nodeModel, NodeUrl.Manage_Restart, "id", id, "copyId", copyId);
                    if (reJson.getCode() == HttpStatus.HTTP_OK) {
                        // ????????????
                        runStatus = true;
                        title = StrUtil.format("???{}???????????????{}?????????{}???????????????????????????????????????,????????????", nodeModel.getName(), id, copyMsg);
                    } else {
                        title = StrUtil.format("???{}???????????????{}?????????{}???????????????????????????????????????,????????????", nodeModel.getName(), id, copyMsg);
                    }
                    context = "???????????????" + reJson;
                } catch (Exception e) {
                    log.error("??????????????????", e);
                    title = StrUtil.format("???{}???????????????{}?????????{}?????????????????????????????????", nodeModel.getName(), id, copyMsg);
                    context = ExceptionUtil.stacktraceToString(e);
                }
            } else {
                title = StrUtil.format("???{}???????????????{}?????????{}??????????????????", nodeModel.getName(), id, copyMsg);
                context = "???????????????";
            }
        }
        if (!pre && !runStatus) {
            // ????????????????????????????????????????????????
            return false;
        }
        MonitorNotifyLog monitorNotifyLog = new MonitorNotifyLog();
        monitorNotifyLog.setStatus(runStatus);
        monitorNotifyLog.setTitle(title);
        monitorNotifyLog.setContent(context);
        monitorNotifyLog.setCreateTime(System.currentTimeMillis());
        monitorNotifyLog.setNodeId(nodeModel.getId());
        monitorNotifyLog.setProjectId(id);
        monitorNotifyLog.setMonitorId(monitorModel.getId());
        //
        this.notifyMsg(nodeModel, monitorNotifyLog);
        return runStatus;
    }

    /**
     * ????????????????????????????????????
     *
     * @param monitorId ??????id
     * @param nodeId    ??????id
     * @param projectId ??????id
     * @return true ???????????????,false ????????????
     */
    private boolean getPreStatus(String monitorId, String nodeId, String projectId) {
        // ??????????????????????????????

        MonitorNotifyLog monitorNotifyLog = new MonitorNotifyLog();
        monitorNotifyLog.setNodeId(nodeId);
        monitorNotifyLog.setProjectId(projectId);
        monitorNotifyLog.setMonitorId(monitorId);

        List<MonitorNotifyLog> queryList = dbMonitorNotifyLogService.queryList(monitorNotifyLog, 1, new Order("createTime", Direction.DESC));
        MonitorNotifyLog entity1 = CollUtil.getFirst(queryList);
        return entity1 == null || entity1.status();
    }

    private void notifyMsg(NodeModel nodeModel, MonitorNotifyLog monitorNotifyLog) {
        List<String> notify = monitorModel.notifyUser();
        // ????????????
        if (monitorNotifyLog.getTitle() == null) {
            return;
        }
        ProjectInfoCacheModel projectInfoCacheModel = projectInfoCacheService.getData(nodeModel.getId(), monitorNotifyLog.getProjectId());
        monitorNotifyLog.setWorkspaceId(projectInfoCacheModel.getWorkspaceId());
        //
        notify.forEach(notifyUser -> this.sendNotifyMsgToUser(monitorNotifyLog, notifyUser));
        //
        this.sendNotifyMsgToWebhook(monitorNotifyLog, nodeModel, projectInfoCacheModel, monitorModel.getWebhook());
    }

    private void sendNotifyMsgToWebhook(MonitorNotifyLog monitorNotifyLog, NodeModel nodeModel, ProjectInfoCacheModel projectInfoCacheModel, String webhook) {
        if (StrUtil.isEmpty(webhook)) {
            return;
        }
        IPlugin plugin = PluginFactory.getPlugin("webhook");
        Map<String, Object> map = new HashMap<>(10);
        map.put("monitorId", monitorModel.getId());
        map.put("monitorName", monitorModel.getName());
        map.put("nodeId", monitorNotifyLog.getNodeId());
        map.put("nodeName", nodeModel.getName());
        map.put("runStatus", monitorNotifyLog.getStatus());
        map.put("projectId", monitorNotifyLog.getProjectId());
        if (projectInfoCacheModel != null) {
            map.put("projectName", projectInfoCacheModel.getName());
        }
        map.put("title", monitorNotifyLog.getTitle());
        map.put("content", monitorNotifyLog.getContent());
        //
        monitorNotifyLog.setNotifyStyle(MonitorModel.NotifyType.webhook.getCode());
        monitorNotifyLog.setNotifyObject(webhook);
        //
        dbMonitorNotifyLogService.insert(monitorNotifyLog);
        String logId = monitorNotifyLog.getId();
        ThreadUtil.execute(() -> {
            try {
                plugin.execute(webhook, map);
                dbMonitorNotifyLogService.updateStatus(logId, true, null);
            } catch (Exception e) {
                log.error("WebHooks ????????????", e);
                dbMonitorNotifyLogService.updateStatus(logId, false, ExceptionUtil.stacktraceToString(e));
            }
        });
    }

    private void sendNotifyMsgToUser(MonitorNotifyLog monitorNotifyLog, String notifyUser) {
        UserModel item = userService.getByKey(notifyUser);
        boolean success = false;
        if (item != null) {
            // ??????
            String email = item.getEmail();
            if (StrUtil.isNotEmpty(email)) {
                monitorNotifyLog.setId(IdUtil.fastSimpleUUID());
                MonitorModel.Notify notify1 = new MonitorModel.Notify(MonitorModel.NotifyType.mail, email);
                monitorNotifyLog.setNotifyStyle(notify1.getStyle());
                monitorNotifyLog.setNotifyObject(notify1.getValue());
                //
                dbMonitorNotifyLogService.insert(monitorNotifyLog);
                this.send(notify1, monitorNotifyLog.getId(), monitorNotifyLog.getTitle(), monitorNotifyLog.getContent());
                success = true;
            }
            // dingding
            String dingDing = item.getDingDing();
            if (StrUtil.isNotEmpty(dingDing)) {
                monitorNotifyLog.setId(IdUtil.fastSimpleUUID());
                MonitorModel.Notify notify1 = new MonitorModel.Notify(MonitorModel.NotifyType.dingding, dingDing);
                monitorNotifyLog.setNotifyStyle(notify1.getStyle());
                monitorNotifyLog.setNotifyObject(notify1.getValue());
                //
                dbMonitorNotifyLogService.insert(monitorNotifyLog);
                this.send(notify1, monitorNotifyLog.getId(), monitorNotifyLog.getTitle(), monitorNotifyLog.getContent());
                success = true;
            }
            // ????????????
            String workWx = item.getWorkWx();
            if (StrUtil.isNotEmpty(workWx)) {
                monitorNotifyLog.setId(IdUtil.fastSimpleUUID());
                MonitorModel.Notify notify1 = new MonitorModel.Notify(MonitorModel.NotifyType.workWx, workWx);
                monitorNotifyLog.setNotifyStyle(notify1.getStyle());
                monitorNotifyLog.setNotifyObject(notify1.getValue());
                //
                dbMonitorNotifyLogService.insert(monitorNotifyLog);
                this.send(notify1, monitorNotifyLog.getId(), monitorNotifyLog.getTitle(), monitorNotifyLog.getContent());
                success = true;
            }
        }
        if (success) {
            return;
        }
        monitorNotifyLog.setId(IdUtil.fastSimpleUUID());
        monitorNotifyLog.setNotifyObject("?????????????????????");
        monitorNotifyLog.setNotifyStyle(MonitorModel.NotifyType.mail.getCode());
        monitorNotifyLog.setNotifyStatus(false);
        monitorNotifyLog.setNotifyError("?????????????????????:" + (item == null ? "??????????????????" : ""));
        dbMonitorNotifyLogService.insert(monitorNotifyLog);
    }

    private void send(MonitorModel.Notify notify, String logId, String title, String context) {
        // ????????????
        ThreadUtil.execute(() -> {
            try {
                NotifyUtil.send(notify, title, context);
                dbMonitorNotifyLogService.updateStatus(logId, true, null);
            } catch (Exception e) {
                log.error("????????????????????????", e);
                dbMonitorNotifyLogService.updateStatus(logId, false, ExceptionUtil.stacktraceToString(e));
            }
        });
    }
}
