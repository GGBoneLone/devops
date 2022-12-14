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
package io.jpom.controller.system;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.db.Entity;
import cn.jiangzeyin.common.JsonMessage;
import cn.jiangzeyin.common.validator.ValidatorItem;
import cn.jiangzeyin.common.validator.ValidatorRule;
import com.alibaba.fastjson.JSONObject;
import io.jpom.common.BaseServerController;
import io.jpom.common.ServerConst;
import io.jpom.common.forward.NodeForward;
import io.jpom.common.forward.NodeUrl;
import io.jpom.model.PageResultDto;
import io.jpom.model.data.NodeModel;
import io.jpom.model.data.WorkspaceEnvVarModel;
import io.jpom.model.user.UserModel;
import io.jpom.permission.ClassFeature;
import io.jpom.permission.Feature;
import io.jpom.permission.MethodFeature;
import io.jpom.permission.SystemPermission;
import io.jpom.service.system.WorkspaceEnvVarService;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;

/**
 * @author bwcx_jzy
 * @since 2021/12/10
 */

@RestController
@Feature(cls = ClassFeature.SYSTEM_WORKSPACE)
@RequestMapping(value = "/system/workspace_env/")
@SystemPermission
public class WorkspaceEnvVarController extends BaseServerController {

    private final WorkspaceEnvVarService workspaceEnvVarService;

    public WorkspaceEnvVarController(WorkspaceEnvVarService workspaceEnvVarService) {
        this.workspaceEnvVarService = workspaceEnvVarService;
    }

    /**
     * ????????????
     *
     * @return json
     */
    @PostMapping(value = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.LIST)
    public String list() {
        PageResultDto<WorkspaceEnvVarModel> listPage = workspaceEnvVarService.listPage(getRequest());
        listPage.each(workspaceEnvVarModel -> {
            Integer privacy = workspaceEnvVarModel.getPrivacy();
            if (privacy != null && privacy == 1) {
                workspaceEnvVarModel.setValue(StrUtil.EMPTY);
            }
        });
        return JsonMessage.getString(200, "", listPage);
    }

    /**
     * ????????????
     *
     * @param workspaceId ??????id
     * @param name        ????????????
     * @param value       ???
     * @param description ??????
     * @return json
     */
    @PostMapping(value = "/edit", produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.EDIT)
    public String edit(String id,
                       @ValidatorItem String workspaceId,
                       @ValidatorItem String name,
                       String value,
                       @ValidatorItem String description,
                       String privacy,
                       String nodeIds) {
        workspaceEnvVarService.checkUserWorkspace(workspaceId);

        this.checkInfo(id, name, workspaceId);
        boolean privacyBool = BooleanUtil.toBoolean(privacy);
        //
        WorkspaceEnvVarModel workspaceModel = new WorkspaceEnvVarModel();
        workspaceModel.setName(name);
        if (privacyBool) {
            if (StrUtil.isNotEmpty(value)) {
                workspaceModel.setValue(value);
            } else {
                // ???????????? ????????????
                Assert.state(StrUtil.isNotEmpty(id), "??????????????????");
            }
        } else {
            // ???????????????
            Assert.hasText(value, "??????????????????");
            workspaceModel.setValue(value);
        }
        workspaceModel.setWorkspaceId(workspaceId);
        workspaceModel.setNodeIds(nodeIds);
        workspaceModel.setDescription(description);
        //
        String oldNodeIds = null;
        if (StrUtil.isEmpty(id)) {
            // ??????
            workspaceModel.setPrivacy(privacyBool ? 1 : 0);
            workspaceEnvVarService.insert(workspaceModel);
        } else {
            WorkspaceEnvVarModel byKey = workspaceEnvVarService.getByKey(id);
            Assert.notNull(byKey, "?????????????????????");
            Assert.state(StrUtil.equals(workspaceId, byKey.getWorkspaceId()), "????????????????????????");
            oldNodeIds = byKey.getNodeIds();
            workspaceModel.setId(id);
            // ????????????
            workspaceModel.setPrivacy(null);
            workspaceEnvVarService.update(workspaceModel);
        }
        this.syncNodeEnvVar(workspaceModel, oldNodeIds);
        return JsonMessage.getString(200, "????????????");
    }

    private void syncDelNodeEnvVar(String name, UserModel user, Collection<String> delNode, String workspaceId) {
        for (String s : delNode) {
            NodeModel byKey = nodeService.getByKey(s);
            Assert.state(StrUtil.equals(workspaceId, byKey.getWorkspaceId()), "??????????????????");
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("name", name);
            JsonMessage<String> jsonMessage = NodeForward.request(byKey, NodeUrl.Workspace_EnvVar_Delete, user, jsonObject);
            Assert.state(jsonMessage.getCode() == 200, "?????? " + byKey.getName() + " ????????????????????????" + jsonMessage.getMsg());
        }
    }

    private void syncNodeEnvVar(WorkspaceEnvVarModel workspaceEnvVarModel, String oldNode) {
        String workspaceId = workspaceEnvVarModel.getWorkspaceId();
        List<String> newNodeIds = StrUtil.splitTrim(workspaceEnvVarModel.getNodeIds(), StrUtil.COMMA);
        List<String> oldNodeIds = StrUtil.splitTrim(oldNode, StrUtil.COMMA);
        Collection<String> delNode = CollUtil.subtract(oldNodeIds, newNodeIds);
        UserModel user = getUser();
        // ??????
        this.syncDelNodeEnvVar(workspaceEnvVarModel.getName(), user, delNode, workspaceId);
        // ??????
        for (String newNodeId : newNodeIds) {
            NodeModel byKey = nodeService.getByKey(newNodeId);
            Assert.state(StrUtil.equals(workspaceId, byKey.getWorkspaceId()), "??????????????????");
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("description", workspaceEnvVarModel.getDescription());
            jsonObject.put("name", workspaceEnvVarModel.getName());
            if (StrUtil.isNotEmpty(workspaceEnvVarModel.getValue())) {
                jsonObject.put("value", workspaceEnvVarModel.getValue());
            } else {
                // ??????
                WorkspaceEnvVarModel byKeyExits = workspaceEnvVarService.getByKey(workspaceEnvVarModel.getId());
                jsonObject.put("value", byKeyExits.getValue());
            }
            JsonMessage<String> jsonMessage = NodeForward.request(byKey, NodeUrl.Workspace_EnvVar_Update, user, jsonObject);
            Assert.state(jsonMessage.getCode() == 200, "?????? " + byKey.getName() + " ????????????????????????" + jsonMessage.getMsg());
        }
    }

    private void checkInfo(String id, String name, String workspaceId) {
        Validator.validateGeneral(name, 1, 50, "???????????? 1-50 ???????????? ?????????????????????");
        //
        Entity entity = Entity.create();
        entity.set("name", name);
        if (!StrUtil.equals(workspaceId, ServerConst.WORKSPACE_GLOBAL)) {
            entity.set("workspaceId", CollUtil.newArrayList(workspaceId, ServerConst.WORKSPACE_GLOBAL));
        }
        if (StrUtil.isNotEmpty(id)) {
            entity.set("id", StrUtil.format(" <> {}", id));
        }
        boolean exists = workspaceEnvVarService.exists(entity);
        Assert.state(!exists, "????????????????????????????????????");
    }


    /**
     * ????????????
     *
     * @param id ?????? ID
     * @return json
     */
    @GetMapping(value = "/delete", produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.DEL)
    public String delete(@ValidatorItem(value = ValidatorRule.NOT_BLANK, msg = "?????? id ????????????") String id,
                         @ValidatorItem String workspaceId) {
        workspaceEnvVarService.checkUserWorkspace(workspaceId);
        WorkspaceEnvVarModel byKey = workspaceEnvVarService.getByKey(id);
        Assert.notNull(byKey, "?????????????????????");
        Assert.state(StrUtil.equals(workspaceId, byKey.getWorkspaceId()), "????????????????????????");
        String oldNodeIds = byKey.getNodeIds();
        List<String> delNode = StrUtil.splitTrim(oldNodeIds, StrUtil.COMMA);
        this.syncDelNodeEnvVar(byKey.getName(), getUser(), delNode, workspaceId);
        // ????????????
        workspaceEnvVarService.delByKey(id);
        return JsonMessage.getString(200, "????????????");
    }
}
