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
package io.jpom.controller.manage;

import cn.hutool.core.util.StrUtil;
import cn.jiangzeyin.common.JsonMessage;
import cn.jiangzeyin.common.validator.ValidatorItem;
import cn.jiangzeyin.common.validator.ValidatorRule;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.jpom.common.BaseAgentController;
import io.jpom.common.commander.AbstractProjectCommander;
import io.jpom.model.data.NodeProjectInfoModel;
import io.jpom.service.manage.ConsoleService;
import io.jpom.socket.ConsoleCommandOp;
import io.jpom.util.CommandUtil;
import io.jpom.util.JvmUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ??????????????????
 *
 * @author jiangzeyin
 * @since 2019/4/17
 */
@RestController
@RequestMapping(value = "/manage/")
@Slf4j
public class ProjectStatusController extends BaseAgentController {

    private final ConsoleService consoleService;

    public ProjectStatusController(ConsoleService consoleService) {
        this.consoleService = consoleService;
    }

    /**
     * ?????????????????????id
     *
     * @param id ??????id
     * @return json
     */
    @RequestMapping(value = "getProjectStatus", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    public String getProjectStatus(@ValidatorItem(value = ValidatorRule.NOT_BLANK, msg = "??????id ?????????") String id, String getCopy) {
        NodeProjectInfoModel nodeProjectInfoModel = tryGetProjectInfoModel();
        Assert.notNull(nodeProjectInfoModel, "??????id?????????");
        JSONObject jsonObject = new JSONObject();
        try {
            CommandUtil.openCache();
            int pid = 0;
            try {
                pid = AbstractProjectCommander.getInstance().getPid(nodeProjectInfoModel, null);
            } catch (Exception e) {
                log.error("????????????pid ??????", e);
            }
            jsonObject.put("pId", pid);
            //
            if (StrUtil.isNotEmpty(getCopy)) {
                List<NodeProjectInfoModel.JavaCopyItem> javaCopyItemList = nodeProjectInfoModel.getJavaCopyItemList();
                JSONArray copys = new JSONArray();
                if (javaCopyItemList != null) {
                    for (NodeProjectInfoModel.JavaCopyItem javaCopyItem : javaCopyItemList) {
                        JSONObject jsonObject1 = new JSONObject();
                        jsonObject1.put("copyId", javaCopyItem.getId());
                        boolean run = AbstractProjectCommander.getInstance().isRun(nodeProjectInfoModel, javaCopyItem);
                        jsonObject1.put("status", run);
                        copys.add(jsonObject1);
                    }
                }
                jsonObject.put("copys", copys);
            }
        } finally {
            CommandUtil.closeCache();
        }
        return JsonMessage.getString(200, "", jsonObject);
    }

    /**
     * ???????????????????????????
     *
     * @param ids ids
     * @return obj
     */
    @RequestMapping(value = "getProjectPort", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    public String getProjectPort(String ids) {
        Assert.hasText(ids, "????????????????????????");
        JSONArray jsonArray = JSONArray.parseArray(ids);
        JSONObject jsonObject = new JSONObject();
        try {
            CommandUtil.openCache();
            for (Object object : jsonArray) {
                String item = object.toString();
                int pid = 0;
                JSONObject itemObj = new JSONObject();
                try {
                    NodeProjectInfoModel projectInfoServiceItem = projectInfoService.getItem(item);
                    pid = AbstractProjectCommander.getInstance().getPid(projectInfoServiceItem, null);
                } catch (Exception e) {
                    log.error("??????????????????", e);
                    itemObj.put("error", e.getMessage());
                }
                String port = AbstractProjectCommander.getInstance().getMainPort(pid);
                itemObj.put("port", port);
                itemObj.put("pid", pid);
                jsonObject.put(item, itemObj);
            }
        } finally {
            CommandUtil.closeCache();
        }
        return JsonMessage.getString(200, "", jsonObject);
    }


    /**
     * ???????????????????????????
     *
     * @param id      ??????id
     * @param copyIds ?????? ids ["aa","ss"]
     * @return obj
     */
    @RequestMapping(value = "getProjectCopyPort", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    public String getProjectPort(String id, String copyIds) {
        if (StrUtil.isEmpty(copyIds) || StrUtil.isEmpty(id)) {
            return JsonMessage.getString(400, "");
        }
        NodeProjectInfoModel nodeProjectInfoModel = getProjectInfoModel();

        JSONArray jsonArray = JSONArray.parseArray(copyIds);
        JSONObject jsonObject = new JSONObject();

        try {
            CommandUtil.openCache();
            for (Object object : jsonArray) {
                String item = object.toString();
                NodeProjectInfoModel.JavaCopyItem copyItem = nodeProjectInfoModel.findCopyItem(item);
                int pid = 0;
                JSONObject itemObj = new JSONObject();
                try {
                    pid = AbstractProjectCommander.getInstance().getPid(nodeProjectInfoModel, copyItem);
                } catch (Exception e) {
                    log.error("??????????????????", e);
                    itemObj.put("error", e.getMessage());
                }
                String port = AbstractProjectCommander.getInstance().getMainPort(pid);
                itemObj.put("port", port);
                itemObj.put("pid", pid);

                jsonObject.put(item, itemObj);
            }
        } finally {
            CommandUtil.closeCache();
        }
        return JsonMessage.getString(200, "", jsonObject);
    }

    @RequestMapping(value = "restart", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    public String restart(@ValidatorItem(value = ValidatorRule.NOT_BLANK, msg = "??????id ?????????") String id, String copyId) {
        NodeProjectInfoModel item = projectInfoService.getItem(id);
        Assert.notNull(item, "???????????????????????????");
        NodeProjectInfoModel.JavaCopyItem copyItem = item.findCopyItem(copyId);

        String result;
        try {
            result = consoleService.execCommand(ConsoleCommandOp.restart, item, copyItem);
            boolean status = AbstractProjectCommander.getInstance().isRun(item, copyItem);
            if (status) {
                return JsonMessage.getString(200, result);
            }
            return JsonMessage.getString(201, "?????????????????????" + result);
        } catch (Exception e) {
            log.error("????????????pid ??????", e);
            result = "error:" + e.getMessage();
            return JsonMessage.getString(500, "?????????????????????" + result);
        }
    }


    @RequestMapping(value = "stop", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    public String stop(@ValidatorItem(value = ValidatorRule.NOT_BLANK, msg = "??????id ?????????") String id, String copyId) {
        NodeProjectInfoModel item = projectInfoService.getItem(id);
        Assert.notNull(item, "???????????????????????????");
        NodeProjectInfoModel.JavaCopyItem copyItem = item.findCopyItem(copyId);

        String result;
        try {
            result = consoleService.execCommand(ConsoleCommandOp.stop, item, copyItem);
            boolean status = AbstractProjectCommander.getInstance().isRun(item, copyItem);
            if (!status) {
                return JsonMessage.getString(200, result);
            }
            return JsonMessage.getString(201, "?????????????????????" + result);
        } catch (Exception e) {
            log.error("????????????pid ??????", e);
            result = "error:" + e.getMessage();
            return JsonMessage.getString(500, "?????????????????????" + result);
        }
    }


    @RequestMapping(value = "start", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    public String start(@ValidatorItem(value = ValidatorRule.NOT_BLANK, msg = "??????id ?????????") String id, String copyId) {
        NodeProjectInfoModel item = projectInfoService.getItem(id);
        Assert.notNull(item, "???????????????????????????");
        NodeProjectInfoModel.JavaCopyItem copyItem = item.findCopyItem(copyId);
        String result;
        try {
            result = consoleService.execCommand(ConsoleCommandOp.start, item, copyItem);
            boolean status = AbstractProjectCommander.getInstance().isRun(item, copyItem);
            if (status) {
                return JsonMessage.getString(200, result);
            }
            return JsonMessage.getString(201, "?????????????????????" + result);
        } catch (Exception e) {
            log.error("????????????pid ??????", e);
            result = "error:" + e.getMessage();
            return JsonMessage.getString(500, "?????????????????????" + result);
        }
    }
}
