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
package io.jpom.service.node.script;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpStatus;
import cn.jiangzeyin.common.JsonMessage;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.jpom.common.BaseServerController;
import io.jpom.common.forward.NodeForward;
import io.jpom.common.forward.NodeUrl;
import io.jpom.model.BaseDbModel;
import io.jpom.model.data.NodeModel;
import io.jpom.model.user.UserModel;
import io.jpom.model.data.WorkspaceModel;
import io.jpom.model.node.ScriptExecuteLogCacheModel;
import io.jpom.service.h2db.BaseNodeService;
import io.jpom.service.node.NodeService;
import io.jpom.service.system.WorkspaceService;
import io.jpom.system.AgentException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ????????????????????????
 *
 * @author bwcx_jzy
 * @since 2021/12/12
 */
@Service
@Slf4j
public class NodeScriptExecuteLogServer extends BaseNodeService<ScriptExecuteLogCacheModel> {

    public NodeScriptExecuteLogServer(NodeService nodeService,
                                      WorkspaceService workspaceService) {
        super(nodeService, workspaceService, "??????????????????");
    }

    @Override
    protected String[] clearTimeColumns() {
        return new String[]{"createTimeMillis"};
    }

    @Override
    public JSONObject getItem(NodeModel nodeModel, String id) {
        return null;
    }

    @Override
    public JSONArray getLitDataArray(NodeModel nodeModel) {
        JsonMessage<?> jsonMessage = NodeForward.requestBySys(nodeModel, NodeUrl.SCRIPT_PULL_EXEC_LOG, "pullCount", 100);
        if (jsonMessage.getCode() != HttpStatus.HTTP_OK) {
            throw new AgentException(jsonMessage.toString());
        }
        Object data = jsonMessage.getData();
        //
        JSONArray jsonArray = (JSONArray) JSONArray.toJSON(data);
        for (Object o : jsonArray) {
            JSONObject jsonObject = (JSONObject) o;
            jsonObject.put("nodeId", nodeModel.getId());
            // ??????
            if (!jsonObject.containsKey("triggerExecType")) {
                jsonObject.put("triggerExecType", 1);
            }
        }
        return jsonArray;

    }

    @Override
    public void syncAllNode() {
        //
    }

    /**
     * ???????????? ??????????????????(??????)
     *
     * @param nodeModel ????????????
     * @return json
     */
    public Collection<String> syncExecuteNodeInc(NodeModel nodeModel) {
        String nodeModelName = nodeModel.getName();
        if (!nodeModel.isOpenStatus()) {
            log.debug("{} ???????????????", nodeModelName);
            return null;
        }
        try {
            JSONArray jsonArray = this.getLitDataArray(nodeModel);
            if (CollUtil.isEmpty(jsonArray)) {
                //
                return null;
            }
            //
            List<ScriptExecuteLogCacheModel> models = jsonArray.toJavaList(this.tClass).stream()
                .filter(item -> {
                    // ??????????????????????????? ????????????
                    return workspaceService.exists(new WorkspaceModel(item.getWorkspaceId()));
                })
                .filter(item -> {
                    // ??????????????????
                    return StrUtil.equals(nodeModel.getWorkspaceId(), item.getWorkspaceId());
                })
                .collect(Collectors.toList());
            // ?????? ?????????????????????????????????
            BaseServerController.resetInfo(UserModel.EMPTY);
            //
            models.forEach(NodeScriptExecuteLogServer.super::upsert);
            String format = StrUtil.format(
                "{} ??????????????? {} ???????????????,?????? {} ???????????????",
                nodeModelName, CollUtil.size(jsonArray),
                CollUtil.size(models));
            log.debug(format);
            return models.stream().map(BaseDbModel::getId).collect(Collectors.toList());
        } catch (Exception e) {
            this.checkException(e, nodeModelName);
            return null;
        } finally {
            BaseServerController.removeEmpty();
        }
    }

    @Override
    protected void executeClearImpl(int h2DbLogStorageCount) {
        super.autoLoopClear("createTimeMillis", h2DbLogStorageCount,
            null,
            executeLogModel -> {
                try {
                    NodeModel nodeModel = nodeService.getByKey(executeLogModel.getNodeId());
                    JsonMessage<Object> jsonMessage = NodeForward.requestBySys(nodeModel, NodeUrl.SCRIPT_DEL_LOG,
                        "id", executeLogModel.getScriptId(), "executeId", executeLogModel.getId());
                    if (jsonMessage.getCode() != HttpStatus.HTTP_OK) {
                        log.warn("{} {} {}", executeLogModel.getNodeId(), executeLogModel.getScriptName(), jsonMessage);
                        return false;
                    }
                    return true;
                } catch (Exception e) {
                    log.error("???????????????????????? {} {}", executeLogModel.getNodeId(), executeLogModel.getScriptName(), e);
                    return false;
                }
            });
    }
}
