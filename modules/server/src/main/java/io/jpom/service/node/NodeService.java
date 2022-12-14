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
package io.jpom.service.node;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.lang.Validator;
import cn.hutool.core.net.NetUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.db.Entity;
import cn.hutool.extra.servlet.ServletUtil;
import cn.jiangzeyin.common.JsonMessage;
import cn.jiangzeyin.common.spring.SpringUtil;
import io.jpom.common.BaseServerController;
import io.jpom.common.Const;
import io.jpom.common.JpomManifest;
import io.jpom.common.forward.NodeForward;
import io.jpom.common.forward.NodeUrl;
import io.jpom.model.data.NodeModel;
import io.jpom.model.data.SshModel;
import io.jpom.model.user.UserModel;
import io.jpom.model.data.WorkspaceModel;
import io.jpom.service.h2db.BaseGroupService;
import io.jpom.service.node.ssh.SshService;
import io.jpom.service.system.WorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @author bwcx_jzy
 * @since 2021/12/4
 */
@Service
@Slf4j
public class NodeService extends BaseGroupService<NodeModel> {

    private final SshService sshService;
    private final WorkspaceService workspaceService;

    public NodeService(SshService sshService, WorkspaceService workspaceService) {
        this.sshService = sshService;
        this.workspaceService = workspaceService;
    }

    @Override
    protected void fillSelectResult(NodeModel data) {
        if (data != null) {
            data.setLoginPwd(null);
        }
    }

    /**
     * ??????????????????????????????
     *
     * @param nodeModel ????????????
     */
    public void testNode(NodeModel nodeModel) {
        //
        int timeOut = ObjectUtil.defaultIfNull(nodeModel.getTimeOut(), 0);
        // ???????????????????????????5????????????????????????????????????????????????
        nodeModel.setTimeOut(5);
        //
        JsonMessage<Object> objectJsonMessage = NodeForward.requestBySys(nodeModel, NodeUrl.Info, "nodeId", nodeModel.getId());
        try {
            JpomManifest jpomManifest = objectJsonMessage.getData(JpomManifest.class);
            Assert.notNull(jpomManifest, "????????????????????????????????????????????????");
        } catch (Exception e) {
            log.error("????????????????????????????????????????????????", e);
            throw new IllegalStateException("????????????????????????,?????????????????????????????????????????????????????????????????????");
        }
        //
        nodeModel.setTimeOut(timeOut);
    }

    public boolean existsByUrl(String url, String workspaceId, String id) {
        //		Entity entity = Entity.create();
        //		entity.set("url", nodeModel.getUrl());
        //		entity.set("workspaceId", workspaceId);
        //		if (StrUtil.isNotEmpty(id)) {
        //			entity.set("id", StrUtil.format(" <> {}", id));
        //		}
        //		boolean exists = super.exists(entity);
        //		Assert.state(!exists, "??????????????????????????????");
        // ??????????????????
        NodeModel nodeModel1 = new NodeModel();
        nodeModel1.setUrl(url);
        nodeModel1.setWorkspaceId(workspaceId);
        List<NodeModel> nodeModels = ObjectUtil.defaultIfNull(super.listByBean(nodeModel1), Collections.EMPTY_LIST);
        Optional<NodeModel> any = nodeModels.stream().filter(nodeModel2 -> !StrUtil.equals(id, nodeModel2.getId())).findAny();
        return any.isPresent();
    }

    /**
     * ?????? ??????
     *
     * @param request ????????????
     */
    public void update(HttpServletRequest request, boolean autoReg) {
        String type = request.getParameter("type");
        boolean create = "add".equalsIgnoreCase(type);
        // ????????????
        NodeModel nodeModel = ServletUtil.toBean(request, NodeModel.class, true);
        String id = nodeModel.getId();
        if (StrUtil.isNotEmpty(id)) {
            String checkId = StrUtil.replace(id, StrUtil.DASHED, StrUtil.UNDERLINE);
            Validator.validateGeneral(checkId, 2, Const.ID_MAX_LEN, "??????id??????????????????2-50??????????????? ????????????????????????");
        }
        Assert.hasText(nodeModel.getName(), "???????????? ????????????");
        //
        this.testHttpProxy(nodeModel.getHttpProxy());
        NodeModel existsNode = super.getByKey(id);
        String workspaceId;
        if (autoReg) {
            if (create) {
                Assert.isNull(existsNode, "??????????????? id ???????????????");
                // ???????????????????????????
                workspaceId = Const.WORKSPACE_DEFAULT_ID;
            } else {
                Assert.notNull(existsNode, "????????????????????????");
                workspaceId = existsNode.getWorkspaceId();
            }
        } else {
            workspaceId = this.getCheckUserWorkspace(request);
        }
        nodeModel.setWorkspaceId(workspaceId);

        //nodeModel.setProtocol(StrUtil.emptyToDefault(nodeModel.getProtocol(), "http"));
        {// ???????????? ??????
            boolean exists = this.existsByUrl(nodeModel.getUrl(), nodeModel.getWorkspaceId(), id);
            Assert.state(!exists, "??????????????????????????????");
        }
        // ?????? ssh
        String sshId = nodeModel.getSshId();
        if (StrUtil.isNotEmpty(sshId)) {
            SshModel byKey = sshService.getByKey(sshId, request);
            Assert.notNull(byKey, "????????? SSH ?????????");
            List<NodeModel> nodeBySshId = this.getNodeBySshId(sshId);
            nodeBySshId = ObjectUtil.defaultIfNull(nodeBySshId, Collections.EMPTY_LIST);
            Optional<NodeModel> any = nodeBySshId.stream().filter(nodeModel2 -> !StrUtil.equals(id, nodeModel2.getId())).findAny();
            Assert.state(!any.isPresent(), "?????????SSH??????????????????????????????");
        }
        if (nodeModel.isOpenStatus()) {
            //
            this.checkLockType(existsNode);
            this.testNode(nodeModel);
        }
        try {
            if (autoReg) {
                BaseServerController.resetInfo(UserModel.EMPTY);
            }
            if (create) {
                if (autoReg) {
                    // ??????????????????????????????
                    nodeModel.setOpenStatus(0);
                    // ???????????? (???????????????????????????)
                    nodeModel.setUnLockType("unassignedWorkspace");
                }
                this.insert(nodeModel);
                // ????????????
                ProjectInfoCacheService projectInfoCacheService = SpringUtil.getBean(ProjectInfoCacheService.class);
                projectInfoCacheService.syncNode(nodeModel);
            } else {
                this.update(nodeModel);
            }
        } finally {
            if (autoReg) {
                BaseServerController.removeEmpty();
            }
        }
    }

    /**
     * ?????? http proxy ????????????
     *
     * @param httpProxy http proxy
     */
    public void testHttpProxy(String httpProxy) {
        if (StrUtil.isNotEmpty(httpProxy)) {
            List<String> split = StrUtil.splitTrim(httpProxy, StrUtil.COLON);
            Assert.isTrue(CollUtil.size(split) == 2, "HTTP???????????????????????????");
            String host = split.get(0);
            int port = Convert.toInt(split.get(1), 0);
            Assert.isTrue(StrUtil.isNotEmpty(host) && NetUtil.isValidPort(port), "HTTP???????????????????????????");
            //
            try {
                NetUtil.netCat(host, port, StrUtil.EMPTY.getBytes());
            } catch (Exception e) {
                log.warn("HTTP?????????????????????:" + httpProxy, e);
                throw new IllegalArgumentException("HTTP?????????????????????:" + e.getMessage());
            }
        }
    }

    /**
     * ????????????????????????
     *
     * @param id          ??????ID
     * @param workspaceId ????????????
     */
    public void unLock(String id, String workspaceId) {
        NodeModel nodeModel = super.getByKey(id);
        Assert.notNull(nodeModel, "?????????????????????");
        //
        WorkspaceModel workspaceModel = workspaceService.getByKey(workspaceId);
        Assert.notNull(workspaceModel, "???????????????????????????");

        NodeModel nodeModel1 = new NodeModel();
        nodeModel1.setId(id);
        nodeModel1.setWorkspaceId(workspaceId);
        nodeModel1.setUnLockType(StrUtil.EMPTY);
        nodeModel1.setOpenStatus(1);
        super.update(nodeModel1);
    }

    /**
     * ??????????????????????????????????????????
     *
     * @param ids            ????????????ID
     * @param nowWorkspaceId ?????????????????????ID
     * @param workspaceId    ???????????????????????????
     */
    public void syncToWorkspace(String ids, String nowWorkspaceId, String workspaceId) {
        StrUtil.splitTrim(ids, StrUtil.COMMA).forEach(id -> {
            NodeModel data = super.getByKey(id, false, entity -> entity.set("workspaceId", nowWorkspaceId));
            Assert.notNull(data, "???????????????????????????");
            //
            NodeModel where = new NodeModel();
            where.setWorkspaceId(workspaceId);
            where.setUrl(data.getUrl());
            NodeModel nodeModel = NodeService.super.queryByBean(where);
            if (nodeModel == null) {
                // ????????????????????????
                data.setId(null);
                data.setWorkspaceId(workspaceId);
                data.setCreateTimeMillis(null);
                data.setModifyTimeMillis(null);
                data.setModifyUser(null);
                // ssh ?????????
                data.setSshId(null);
                NodeService.super.insert(data);
            } else {
                // ????????????
                NodeModel update = new NodeModel(nodeModel.getId());
                update.setLoginName(data.getLoginName());
                update.setLoginPwd(data.getLoginPwd());
                update.setProtocol(data.getProtocol());
                update.setHttpProxy(data.getHttpProxy());
                update.setHttpProxyType(data.getHttpProxyType());
                NodeService.super.updateById(update);
            }
        });
    }

    /**
     * ???????????????????????????
     *
     * @param nodeModel ??????
     */
    private void checkLockType(NodeModel nodeModel) {
        if (nodeModel == null) {
            return;
        }
        // ??????????????????
        if (StrUtil.isNotEmpty(nodeModel.getUnLockType())) {
            //
            Assert.state(!StrUtil.equals(nodeModel.getUnLockType(), "unassignedWorkspace"), "????????????????????????????????????,?????????");
        }
    }

    @Override
    public void insert(NodeModel nodeModel) {
        this.fillNodeInfo(nodeModel);
        super.insert(nodeModel);
        this.updateDuplicateNode(nodeModel);
    }

    @Override
    public void insertNotFill(NodeModel nodeModel) {
        nodeModel.setWorkspaceId(StrUtil.emptyToDefault(nodeModel.getWorkspaceId(), Const.WORKSPACE_DEFAULT_ID));
        this.fillNodeInfo(nodeModel);
        super.insertNotFill(nodeModel);
        this.updateDuplicateNode(nodeModel);
    }

    /**
     * ??????????????????
     *
     * @param nodeModel ??????
     */
    private void fillNodeInfo(NodeModel nodeModel) {
        nodeModel.setProtocol(StrUtil.emptyToDefault(nodeModel.getProtocol(), "http"));
        nodeModel.setCycle(0);
        nodeModel.setOpenStatus(ObjectUtil.defaultIfNull(nodeModel.getOpenStatus(), 0));
    }

    @Override
    public int delByKey(String keyValue) {
        return super.delByKey(keyValue);
    }

    @Override
    public int del(Entity where) {
        return super.del(where);
    }

    @Override
    public int updateById(NodeModel info) {
        int updateById = super.updateById(info);
        if (updateById > 0) {
            this.updateDuplicateNode(info);
        }
        return updateById;
    }

    /**
     * ????????????????????? ????????????
     *
     * @param info ????????????
     */
    private void updateDuplicateNode(NodeModel info) {
//        if (StrUtil.hasEmpty(info.getUrl(), info.getLoginName(), info.getLoginPwd())) {
//            return;
//        }
//        NodeModel update = new NodeModel();
//        update.setLoginName(info.getLoginName());
//        update.setLoginPwd(info.getLoginPwd());
//        //
//        NodeModel where = new NodeModel();
//        where.setUrl(info.getUrl());
//        int updateCount = super.update(super.dataBeanToEntity(update), super.dataBeanToEntity(where));
//        if (updateCount > 1) {
//            log.debug("update duplicate node {} {}", info.getUrl(), updateCount);
//        }
    }

    /**
     * ?????? url ??????
     *
     * @return list
     */
    public List<NodeModel> listDeDuplicationByUrl() {
        String sql = "select url,max(loginName) as loginName,max(loginPwd) as loginPwd,max(protocol) as protocol from " + super.getTableName() + "  group  by url";
        List<Entity> query = this.query(sql);
        if (query != null) {
            return query.stream().map((entity -> this.entityToBean(entity, this.tClass))).collect(Collectors.toList());
        }
        return null;
    }

    /**
     * ?????? url ??????
     *
     * @return list
     */
    public List<String> getDeDuplicationByUrl() {
        String sql = "select url from " + super.getTableName() + "  group  by url";
        List<Entity> query = this.query(sql);
        if (query != null) {
            return query.stream().map((entity -> entity.getStr("url"))).collect(Collectors.toList());
        }
        return null;
    }

    public List<NodeModel> getNodeBySshId(String sshId) {
        NodeModel nodeModel = new NodeModel();
        nodeModel.setSshId(sshId);
        return super.listByBean(nodeModel);
    }
}
