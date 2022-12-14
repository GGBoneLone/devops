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
package io.jpom.controller.monitor;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.RegexPool;
import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.StrUtil;
import cn.jiangzeyin.common.JsonMessage;
import cn.jiangzeyin.common.validator.ValidatorItem;
import cn.jiangzeyin.common.validator.ValidatorRule;
import com.alibaba.fastjson.JSONArray;
import io.jpom.common.BaseServerController;
import io.jpom.model.PageResultDto;
import io.jpom.model.data.MonitorModel;
import io.jpom.model.user.UserModel;
import io.jpom.permission.ClassFeature;
import io.jpom.permission.Feature;
import io.jpom.permission.MethodFeature;
import io.jpom.service.dblog.DbMonitorNotifyLogService;
import io.jpom.service.monitor.MonitorService;
import io.jpom.service.node.ProjectInfoCacheService;
import io.jpom.service.user.UserService;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.util.List;

/**
 * ????????????
 *
 * @author bwcx_jzy
 * @since 2019/6/15
 */
@RestController
@RequestMapping(value = "/monitor")
@Feature(cls = ClassFeature.MONITOR)
public class MonitorListController extends BaseServerController {

    private final MonitorService monitorService;
    private final DbMonitorNotifyLogService dbMonitorNotifyLogService;
    private final UserService userService;
    private final ProjectInfoCacheService projectInfoCacheService;

    public MonitorListController(MonitorService monitorService,
                                 DbMonitorNotifyLogService dbMonitorNotifyLogService,
                                 UserService userService,
                                 ProjectInfoCacheService projectInfoCacheService) {
        this.monitorService = monitorService;
        this.dbMonitorNotifyLogService = dbMonitorNotifyLogService;
        this.userService = userService;
        this.projectInfoCacheService = projectInfoCacheService;
    }

    /**
     * ??????????????????
     *
     * @return json
     */
    @RequestMapping(value = "getMonitorList", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.LIST)
    public String getMonitorList() {
        PageResultDto<MonitorModel> pageResultDto = monitorService.listPage(getRequest());
        return JsonMessage.getString(200, "", pageResultDto);
    }

    /**
     * ????????????
     *
     * @param id id
     * @return json
     */
    @RequestMapping(value = "deleteMonitor", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.DEL)
    public String deleteMonitor(@ValidatorItem(value = ValidatorRule.NOT_BLANK, msg = "????????????") String id) throws SQLException {
        //
        HttpServletRequest request = getRequest();
        int delByKey = monitorService.delByKey(id, request);
        if (delByKey > 0) {
            // ????????????
            dbMonitorNotifyLogService.delByWorkspace(request, entity -> entity.set("monitorId", id));
        }
        return JsonMessage.getString(200, "????????????");
    }


    /**
     * ?????????????????????
     *
     * @param id         id
     * @param name       name
     * @param notifyUser user
     * @return json
     */
    @RequestMapping(value = "updateMonitor", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.EDIT)
    public String updateMonitor(String id,
                                @ValidatorItem(value = ValidatorRule.NOT_BLANK, msg = "????????????????????????") String name,
                                @ValidatorItem(msg = "?????????????????????") String execCron,
                                String notifyUser, String webhook) {
        String status = getParameter("status");
        String autoRestart = getParameter("autoRestart");

        JSONArray jsonArray = JSONArray.parseArray(notifyUser);
//		List<String> notifyUsers = jsonArray.toJavaList(String.class);
        List<String> notifyUserList = jsonArray.toJavaList(String.class);
        if (CollUtil.isNotEmpty(notifyUserList)) {
            for (String userId : notifyUserList) {
                Assert.state(userService.exists(new UserModel(userId)), "????????????????????????" + userId);
            }
        }
        String projects = getParameter("projects");
        JSONArray projectsArray = JSONArray.parseArray(projects);
        List<MonitorModel.NodeProject> nodeProjects = projectsArray.toJavaList(MonitorModel.NodeProject.class);
        Assert.notEmpty(nodeProjects, "???????????????????????????");
        for (MonitorModel.NodeProject nodeProject : nodeProjects) {
            Assert.notEmpty(nodeProject.getProjects(), "???????????????????????????");
            for (String project : nodeProject.getProjects()) {
                boolean exists = projectInfoCacheService.exists(nodeProject.getNode(), project);
                Assert.state(exists, "????????????????????????" + project);
            }
        }
        //
        // ????????????
        if (StrUtil.isNotEmpty(webhook)) {
            Validator.validateMatchRegex(RegexPool.URL_HTTP, webhook, "WebHooks ???????????????");
        }
        Assert.state(CollUtil.isNotEmpty(notifyUserList) || StrUtil.isNotEmpty(webhook), "??????????????????????????????????????????webhook");

        boolean start = "on".equalsIgnoreCase(status);
        MonitorModel monitorModel = monitorService.getByKey(id);
        if (monitorModel == null) {
            monitorModel = new MonitorModel();
        }
        monitorModel.setAutoRestart("on".equalsIgnoreCase(autoRestart));
        monitorModel.setExecCron(this.checkCron(execCron));
        monitorModel.projects(nodeProjects);
        monitorModel.setStatus(start);
        monitorModel.setWebhook(webhook);
        monitorModel.notifyUser(notifyUserList);
        monitorModel.setName(name);

        if (StrUtil.isEmpty(id)) {
            //????????????
            monitorService.insert(monitorModel);
            return JsonMessage.getString(200, "????????????");
        }
        HttpServletRequest request = getRequest();
        monitorService.updateById(monitorModel, request);
        return JsonMessage.getString(200, "????????????");
    }
}
