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
package io.jpom.service.h2db;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.db.Entity;
import cn.hutool.extra.servlet.ServletUtil;
import cn.jiangzeyin.common.spring.SpringUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import io.jpom.common.BaseServerController;
import io.jpom.common.Const;
import io.jpom.model.BaseNodeModel;
import io.jpom.model.PageResultDto;
import io.jpom.model.data.NodeModel;
import io.jpom.model.user.UserModel;
import io.jpom.model.data.WorkspaceModel;
import io.jpom.service.node.NodeService;
import io.jpom.service.system.WorkspaceService;
import io.jpom.system.AgentException;
import io.jpom.system.AuthorizeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author bwcx_jzy
 * @since 2021/12/5
 */
@Slf4j
public abstract class BaseNodeService<T extends BaseNodeModel> extends BaseWorkspaceService<T> {

    protected final NodeService nodeService;
    protected final WorkspaceService workspaceService;
    private final String dataName;

    protected BaseNodeService(NodeService nodeService,
                              WorkspaceService workspaceService,
                              String dataName) {
        this.nodeService = nodeService;
        this.workspaceService = workspaceService;
        this.dataName = dataName;
    }

    public PageResultDto<T> listPageNode(HttpServletRequest request) {
        // ????????????????????????
        Map<String, String> paramMap = ServletUtil.getParamMap(request);
        String workspaceId = this.getCheckUserWorkspace(request);
        paramMap.put("workspaceId", workspaceId);
        // ????????????
        String nodeId = paramMap.get(BaseServerController.NODE_ID);
        Assert.notNull(nodeId, "??????????????????ID");
        NodeService nodeService = SpringUtil.getBean(NodeService.class);
        NodeModel nodeModel = nodeService.getByKey(nodeId);
        Assert.notNull(nodeModel, "????????????????????????");
        paramMap.put("nodeId", nodeId);
        return super.listPage(paramMap);
    }


    /**
     * ???????????????????????????
     */
    public void syncAllNode() {
        ThreadUtil.execute(() -> {
            List<NodeModel> list = nodeService.list();
            if (CollUtil.isEmpty(list)) {
                log.debug("??????????????????");
                return;
            }
            // ?????? ??????????????????????????????
            list.sort((o1, o2) -> {
                if (StrUtil.equals(o1.getWorkspaceId(), Const.WORKSPACE_DEFAULT_ID)) {
                    return 1;
                }
                if (StrUtil.equals(o2.getWorkspaceId(), Const.WORKSPACE_DEFAULT_ID)) {
                    return 1;
                }
                return 0;
            });
            for (NodeModel nodeModel : list) {
                this.syncNode(nodeModel);
            }
        });
    }


    /**
     * ?????????????????????
     *
     * @param nodeModel ??????
     */
    public void syncNode(final NodeModel nodeModel) {
        ThreadUtil.execute(() -> this.syncExecuteNode(nodeModel));
    }

    /**
     * ???????????? ??????????????????
     *
     * @param nodeModel ????????????
     * @return json
     */
    public String syncExecuteNode(NodeModel nodeModel) {
        String nodeModelName = nodeModel.getName();
        if (!nodeModel.isOpenStatus()) {
            log.debug("{} ???????????????", nodeModelName);
            return "???????????????";
        }
        try {
            JSONArray jsonArray = this.getLitDataArray(nodeModel);
            if (CollUtil.isEmpty(jsonArray)) {
                Entity entity = Entity.create();
                entity.set("nodeId", nodeModel.getId());
                int del = super.del(entity);
                //
                log.debug("{} ???????????????????????????{}", nodeModelName, dataName);
                return "???????????????????????????" + dataName;
            }
            // ???????????????????????????
            T where = ReflectUtil.newInstance(this.tClass);
            where.setWorkspaceId(nodeModel.getWorkspaceId());
            where.setNodeId(nodeModel.getId());
            List<T> cacheAll = super.listByBean(where);
            cacheAll = ObjectUtil.defaultIfNull(cacheAll, Collections.EMPTY_LIST);
            Set<String> cacheIds = cacheAll.stream()
                .map(BaseNodeModel::dataId)
                .collect(Collectors.toSet());
            //
            List<T> projectInfoModels = jsonArray.toJavaList(this.tClass);
            List<T> models = projectInfoModels.stream()
                .peek(item -> this.fullData(item, nodeModel))
                .filter(item -> {
                    // ??????????????????????????? ????????????
                    return workspaceService.exists(new WorkspaceModel(item.getWorkspaceId()));
                })
                .filter(projectInfoModel -> {
                    // ??????????????????
                    return StrUtil.equals(nodeModel.getWorkspaceId(), projectInfoModel.getWorkspaceId());
                })
                .peek(item -> cacheIds.remove(item.dataId()))
                .collect(Collectors.toList());
            // ?????? ?????????????????????????????????
            BaseServerController.resetInfo(UserModel.EMPTY);
            //
            models.forEach(BaseNodeService.super::upsert);
            // ????????????
            Set<String> strings = cacheIds.stream()
                .map(s -> BaseNodeModel.fullId(nodeModel.getWorkspaceId(), nodeModel.getId(), s))
                .collect(Collectors.toSet());
            if (CollUtil.isNotEmpty(strings)) {
                super.delByKey(strings, null);
            }
            String format = StrUtil.format(
                "{} ??????????????? {} ???{},???????????? {} ???{},?????? {} ???{},?????? {} ?????????",
                nodeModelName, CollUtil.size(jsonArray), dataName,
                CollUtil.size(cacheAll), dataName,
                CollUtil.size(models), dataName,
                CollUtil.size(strings));
            log.debug(format);
            return format;
        } catch (Exception e) {
            return this.checkException(e, nodeModelName);
        } finally {
            BaseServerController.removeEmpty();
        }
    }

    protected String checkException(Exception e, String nodeModelName) {
        if (e instanceof AgentException) {
            AgentException agentException = (AgentException) e;
            log.error("{} ???????????? {}", nodeModelName, agentException.getMessage());
            return "????????????" + agentException.getMessage();
        } else if (e instanceof AuthorizeException) {
            AuthorizeException authorizeException = (AuthorizeException) e;
            log.error("{} ???????????? {}", nodeModelName, authorizeException.getMessage());
            return "????????????" + authorizeException.getMessage();
        } else if (e instanceof JSONException) {
            log.error("{} ?????????????????? {}", nodeModelName, e.getMessage());
            return "??????????????????" + e.getMessage();
        }
        log.error("????????????" + dataName + "??????:" + nodeModelName, e);
        return "????????????" + dataName + "??????" + e.getMessage();
    }

    /**
     * ?????????????????????
     *
     * @param nodeModel ??????
     */
    public void syncNode(final NodeModel nodeModel, String id) {
        String nodeModelName = nodeModel.getName();
        if (!nodeModel.isOpenStatus()) {
            log.debug("{} ???????????????", nodeModelName);
            return;
        }
        ThreadUtil.execute(() -> {
            try {
                JSONObject data = this.getItem(nodeModel, id);
                if (data == null) {
                    // ??????
                    String fullId = BaseNodeModel.fullId(nodeModel.getWorkspaceId(), nodeModel.getId(), id);
                    super.delByKey(fullId);
                    return;
                }
                T projectInfoModel = data.toJavaObject(this.tClass);
                this.fullData(projectInfoModel, nodeModel);
                // ?????? ?????????????????????????????????
                BaseServerController.resetInfo(UserModel.EMPTY);
                //
                super.upsert(projectInfoModel);
            } catch (Exception e) {
                this.checkException(e, nodeModelName);
            } finally {
                BaseServerController.removeEmpty();
            }
        });
    }

    /**
     * ????????????ID
     *
     * @param item      ??????
     * @param nodeModel ??????
     */
    private void fullData(T item, NodeModel nodeModel) {
        item.dataId(item.getId());
        item.setNodeId(nodeModel.getId());
        if (StrUtil.isEmpty(item.getWorkspaceId())) {
            item.setWorkspaceId(nodeModel.getWorkspaceId());
        }
        item.setId(item.fullId());
    }

    /**
     * ???????????? ??????????????????
     *
     * @param nodeId  ??????
     * @param request ??????
     * @return ????????????
     */
    public int delCache(String nodeId, HttpServletRequest request) {
        String checkUserWorkspace = this.getCheckUserWorkspace(request);
        Entity entity = Entity.create();
        entity.set("nodeId", nodeId);
        entity.set("workspaceId", checkUserWorkspace);
        return super.del(entity);
    }

    /**
     * ???????????? ??????????????????
     *
     * @param dataId  ??????ID
     * @param nodeId  ??????
     * @param request ??????
     * @return ????????????
     */
    public int delCache(String dataId, String nodeId, HttpServletRequest request) {
        String checkUserWorkspace = this.getCheckUserWorkspace(request);
        T data = ReflectUtil.newInstance(this.tClass);
        data.setNodeId(nodeId);
        data.dataId(dataId);
        data.setWorkspaceId(checkUserWorkspace);
        Entity entity = super.dataBeanToEntity(data);
        return super.del(entity);
    }

    /**
     * ?????? ???????????????ID????????????
     *
     * @param nodeId ??????ID
     * @param dataId ??????ID
     * @return data
     */
    @Override
    public T getData(String nodeId, String dataId) {
        T data = ReflectUtil.newInstance(this.tClass);
        data.setNodeId(nodeId);
        data.dataId(dataId);
        return super.queryByBean(data);
    }

    /**
     * ??????????????????
     *
     * @param nodeModel ??????
     * @param id        ??????ID
     * @return json
     */
    public abstract JSONObject getItem(NodeModel nodeModel, String id);

    /**
     * ??????????????????
     *
     * @param nodeModel ??????
     * @return json
     */
    public abstract JSONArray getLitDataArray(NodeModel nodeModel);
}
