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
package io.jpom.service.user;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.Week;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.db.Entity;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.jpom.model.data.WorkspaceModel;
import io.jpom.model.user.UserBindWorkspaceModel;
import io.jpom.model.user.UserModel;
import io.jpom.model.user.UserPermissionGroupBean;
import io.jpom.permission.MethodFeature;
import io.jpom.service.h2db.BaseDbService;
import io.jpom.service.system.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.time.DayOfWeek;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author bwcx_jzy
 * @since 2021/12/4
 */
@Service
public class UserBindWorkspaceService extends BaseDbService<UserBindWorkspaceModel> {

    private final WorkspaceService workspaceService;
    private final UserPermissionGroupServer userPermissionGroupServer;

    /**
     * ???????????????
     */
    public static final String SYSTEM_USER = "-systemUser";
    /**
     * ssh ????????????????????????
     */
    public static final String SSH_COMMAND_NOT_LIMITED = "-sshCommandNotLimited";

    public UserBindWorkspaceService(WorkspaceService workspaceService,
                                    UserPermissionGroupServer userPermissionGroupServer) {
        this.workspaceService = workspaceService;
        this.userPermissionGroupServer = userPermissionGroupServer;
    }

    /**
     * ?????????????????????????????????
     *
     * @param userId    ??????ID
     * @param workspace ??????????????????
     */
    public void updateUserWorkspace(String userId, List<String> workspace) {
        Assert.notEmpty(workspace, "??????????????????????????????");
        List<UserBindWorkspaceModel> list = new HashSet<>(workspace).stream()
            .filter(s -> {
                // ??????
                s = StrUtil.removeSuffix(s, SYSTEM_USER);
                s = StrUtil.removeSuffix(s, SSH_COMMAND_NOT_LIMITED);
                MethodFeature[] values = MethodFeature.values();
                for (MethodFeature value : values) {
                    s = StrUtil.removeSuffix(s, StrUtil.DASHED + value.name());
                }
                return workspaceService.exists(new WorkspaceModel(s));
            })
            .map(s -> {
                UserBindWorkspaceModel userBindWorkspaceModel = new UserBindWorkspaceModel();
                userBindWorkspaceModel.setWorkspaceId(s);
                userBindWorkspaceModel.setUserId(userId);
                userBindWorkspaceModel.setId(UserBindWorkspaceModel.getId(userId, s));
                return userBindWorkspaceModel;
            })
            .collect(Collectors.toList());
        // ?????????????????????
        UserBindWorkspaceModel userBindWorkspaceModel = new UserBindWorkspaceModel();
        userBindWorkspaceModel.setUserId(userId);
        super.del(super.dataBeanToEntity(userBindWorkspaceModel));
        // ????????????
        super.insert(list);
    }

    /**
     * ?????????????????????????????????
     *
     * @param userId ??????ID
     * @return list
     */
    public List<UserBindWorkspaceModel> listUserWorkspace(String userId) {
        UserBindWorkspaceModel userBindWorkspaceModel = new UserBindWorkspaceModel();
        userBindWorkspaceModel.setUserId(userId);
        return super.listByBean(userBindWorkspaceModel);
    }

    /**
     * ????????????????????????????????????????????????
     *
     * @param workspaceId ????????????ID
     * @return true ???????????????
     */
    public boolean existsWorkspace(String workspaceId) {
        UserBindWorkspaceModel userBindWorkspaceModel = new UserBindWorkspaceModel();
        userBindWorkspaceModel.setWorkspaceId(workspaceId);
        return super.exists(userBindWorkspaceModel);
    }

    /**
     * ?????????????????????????????????
     *
     * @param userModel ??????
     * @return list
     */
    public List<WorkspaceModel> listUserWorkspaceInfo(UserModel userModel) {
        if (userModel.isSuperSystemUser()) {
            // ??????????????????????????????????????????
            return workspaceService.list();
        }
        String permissionGroup = userModel.getPermissionGroup();
        List<String> list = StrUtil.splitTrim(permissionGroup, StrUtil.AT);
        list = ObjectUtil.defaultIfNull(list, new ArrayList<>());
        // ???????????????
        list.add(userModel.getId());
        Entity entity = Entity.create();
        entity.set("userId", list);
        List<UserBindWorkspaceModel> userBindWorkspaceModels = super.listByEntity(entity);
        Assert.notEmpty(userBindWorkspaceModels, "??????????????????????????????,?????????????????????");
        List<String> collect = userBindWorkspaceModels.stream().map(UserBindWorkspaceModel::getWorkspaceId).collect(Collectors.toList());
        return workspaceService.listById(collect);
    }

    /**
     * ??????
     *
     * @param userId ??????ID
     */
    public void deleteByUserId(String userId) {
        UserBindWorkspaceModel bindWorkspaceModel = new UserBindWorkspaceModel();
        bindWorkspaceModel.setUserId(userId);
        Entity where = super.dataBeanToEntity(bindWorkspaceModel);
        super.del(where);
    }

    /**
     * ???????????? ??????????????????????????????
     *
     * @param userModel   ??????
     * @param workspaceId ????????????
     * @return list
     */
    private List<UserBindWorkspaceModel> existsList(UserModel userModel, String workspaceId) {
        String permissionGroup = userModel.getPermissionGroup();
        List<String> list = StrUtil.splitTrim(permissionGroup, StrUtil.AT);
        list = list.stream()
            .map(s -> UserBindWorkspaceModel.getId(s, workspaceId))
            .collect(Collectors.toList());
        // ???????????????
        list.add(UserBindWorkspaceModel.getId(userModel.getId(), workspaceId));
        return this.listById(list);
    }

    /**
     * ???????????? ??????????????????????????????
     *
     * @param userModel   ??????
     * @param workspaceId ????????????
     * @return true ??????
     */
    public boolean exists(UserModel userModel, String workspaceId) {
        List<UserBindWorkspaceModel> workspaceModels = this.existsList(userModel, workspaceId);
        return CollUtil.isNotEmpty(workspaceModels);
    }

    /**
     * ????????????????????????????????????????????????
     *
     * @param userModel   ??????
     * @param workspaceId ????????????ID
     * @return Permission Result
     */
    public UserBindWorkspaceModel.PermissionResult checkPermission(UserModel userModel, String workspaceId) {
        List<UserBindWorkspaceModel> workspaceModels = this.existsList(userModel, workspaceId);
        if (CollUtil.isEmpty(workspaceModels)) {
            return UserBindWorkspaceModel.PermissionResult.builder()
                .state(UserBindWorkspaceModel.PermissionResultEnum.FAIL)
                .msg("???????????????????????????:-3")
                .build();
        }
        List<String> permissionGroupIds = workspaceModels.stream()
            .map(UserBindWorkspaceModel::getUserId)
            .collect(Collectors.toList());
        List<UserPermissionGroupBean> permissionGroups = userPermissionGroupServer.listById(permissionGroupIds);
        if (CollUtil.isEmpty(permissionGroups)) {
            return UserBindWorkspaceModel.PermissionResult.builder()
                .state(UserBindWorkspaceModel.PermissionResultEnum.FAIL)
                .msg("???????????????????????????:-2")
                .build();
        }
        // ??????????????????
        Optional<JSONObject> prohibitExecuteRule = this.findProhibitExecuteRule(permissionGroups);
        if (prohibitExecuteRule.isPresent()) {
            String msg = prohibitExecuteRule.map(jsonObject -> {
                String reason = jsonObject.getString("reason");
                String startTime = jsonObject.getString("startTime");
                String endTime = jsonObject.getString("endTime");
                if (StrUtil.isEmpty(reason)) {
                    return StrUtil.format("?????????????????????????????????????????? {} ??? {}", startTime, endTime);
                }
                return StrUtil.format("??????????????????{} {} ??? {}", reason, startTime, endTime);
            }).orElse("??????????????????????????????????????????");
            return UserBindWorkspaceModel.PermissionResult.builder()
                .state(UserBindWorkspaceModel.PermissionResultEnum.MISS_PROHIBIT)
                .msg(msg)
                .build();
        }
        // ??????????????????
        return this.checkAllowExecute(permissionGroups);
    }

    /**
     * ??????????????????????????????
     *
     * @param permissionGroups ?????????
     * @return ??????
     */
    private UserBindWorkspaceModel.PermissionResult checkAllowExecute(List<UserPermissionGroupBean> permissionGroups) {
        List<JSONObject> allowExecuteListRule = permissionGroups.stream()
            .map(UserPermissionGroupBean::getAllowExecute)
            .filter(Objects::nonNull)
            .map(JSONArray::parseArray)
            .filter(CollUtil::isNotEmpty)
            .flatMap(jsonArray -> jsonArray.stream().map(o -> (JSONObject) o))
            .collect(Collectors.toList());
        if (CollUtil.isEmpty(allowExecuteListRule)) {
            // ?????????????????????????????????
            return UserBindWorkspaceModel.PermissionResult.builder().state(UserBindWorkspaceModel.PermissionResultEnum.SUCCESS).build();
        }
        Optional<JSONObject> allowExecuteRule = allowExecuteListRule.stream()
            .filter(jsonObject -> {
                DateTime now = DateTime.now();
                Week nowWeek = now.dayOfWeekEnum();
                int nowWeekInt = nowWeek.getIso8601Value();
                JSONArray week = jsonObject.getJSONArray("week");
                if (CollUtil.isEmpty(week)) {
                    return false;
                }
                if (!CollUtil.contains(week, nowWeekInt)) {
                    return false;
                }
                String startTime = jsonObject.getString("startTime");
                String endTime = jsonObject.getString("endTime");
                DateTime startDate = DateUtil.parseTimeToday(startTime);
                DateTime endDate = DateUtil.parseTimeToday(endTime);
                return DateUtil.isIn(DateTime.now(), startDate, endDate);
            })
            .findAny();
        if (allowExecuteRule.isPresent()) {
            // ????????????
            return UserBindWorkspaceModel.PermissionResult.builder().state(UserBindWorkspaceModel.PermissionResultEnum.SUCCESS).build();
        }
        // ??????????????????
        String ruleStr = allowExecuteListRule.stream().map(jsonObject -> {
            JSONArray week = jsonObject.getJSONArray("week");
            String weekStr = week.stream()
                .map(o -> Convert.toInt(o, 0))
                .map(weekInt -> {
                    DayOfWeek dayOfWeek = DayOfWeek.of(weekInt);
                    return Week.of(dayOfWeek);
                })
                .map(week1 -> week1.toChinese(StrUtil.EMPTY))
                .collect(Collectors.joining(StrUtil.COMMA));
            String startTime = jsonObject.getString("startTime");
            String endTime = jsonObject.getString("endTime");
            return StrUtil.format("???{} ??? {} ??? {}", weekStr, startTime, endTime);
        }).collect(Collectors.joining(StrUtil.SPACE));
        return UserBindWorkspaceModel.PermissionResult.builder()
            .state(UserBindWorkspaceModel.PermissionResultEnum.MISS_PERIOD)
            .msg("????????????????????????????????????????????????????????????,???????????????:" + ruleStr)
            .build();
    }

    /**
     * ????????????????????????
     *
     * @param permissionGroups ???????????????
     * @return ??????????????????????????????
     */
    private Optional<JSONObject> findProhibitExecuteRule(List<UserPermissionGroupBean> permissionGroups) {
        return permissionGroups.stream()
            .map(UserPermissionGroupBean::getProhibitExecute)
            .filter(Objects::nonNull)
            .map(JSONArray::parseArray)
            .filter(CollUtil::isNotEmpty)
            .flatMap(jsonArray -> jsonArray.stream().map(o -> (JSONObject) o))
            .filter(jsonObject -> {
                String startTime = jsonObject.getString("startTime");
                String endTime = jsonObject.getString("endTime");
                if (StrUtil.hasEmpty(startTime, endTime)) {
                    return false;
                }
                DateTime startDate = DateUtil.parse(startTime);
                DateTime endDate = DateUtil.parse(endTime);
                return DateUtil.isIn(DateTime.now(), startDate, endDate);
            })
            .findFirst();
    }
}
