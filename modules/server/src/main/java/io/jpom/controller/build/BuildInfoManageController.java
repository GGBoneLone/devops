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
package io.jpom.controller.build;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import cn.jiangzeyin.common.JsonMessage;
import cn.jiangzeyin.common.validator.ValidatorConfig;
import cn.jiangzeyin.common.validator.ValidatorItem;
import cn.jiangzeyin.common.validator.ValidatorRule;
import com.alibaba.fastjson.JSONObject;
import io.jpom.build.BuildExecuteService;
import io.jpom.build.BuildExtraModule;
import io.jpom.build.BuildUtil;
import io.jpom.build.ReleaseManage;
import io.jpom.common.BaseServerController;
import io.jpom.model.BaseEnum;
import io.jpom.model.data.BuildInfoModel;
import io.jpom.model.user.UserModel;
import io.jpom.model.enums.BuildStatus;
import io.jpom.model.log.BuildHistoryLog;
import io.jpom.permission.ClassFeature;
import io.jpom.permission.Feature;
import io.jpom.permission.MethodFeature;
import io.jpom.service.dblog.BuildInfoService;
import io.jpom.service.dblog.DbBuildHistoryLogService;
import io.jpom.util.FileUtils;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.Objects;

/**
 * new build info manage controller
 * ` *
 *
 * @author Hotstrip
 * @since 2021-08-23
 */
@RestController
@Feature(cls = ClassFeature.BUILD)
public class BuildInfoManageController extends BaseServerController {


    private final BuildInfoService buildInfoService;
    private final DbBuildHistoryLogService dbBuildHistoryLogService;
    private final BuildExecuteService buildExecuteService;

    public BuildInfoManageController(BuildInfoService buildInfoService,
                                     DbBuildHistoryLogService dbBuildHistoryLogService,
                                     BuildExecuteService buildExecuteService) {
        this.buildInfoService = buildInfoService;
        this.dbBuildHistoryLogService = dbBuildHistoryLogService;
        this.buildExecuteService = buildExecuteService;
    }

    /**
     * ????????????
     *
     * @param id id
     * @return json
     */
    @RequestMapping(value = "/build/manage/start", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.EXECUTE)
    public String start(@ValidatorItem(value = ValidatorRule.NOT_BLANK, msg = "????????????") String id,
                        String buildRemark,
                        String resultDirFile,
                        String branchName,
                        String branchTagName) {
        BuildInfoModel item = buildInfoService.getByKey(id, getRequest());
        Assert.notNull(item, "??????????????????");
        // ????????????
        BuildInfoModel update = new BuildInfoModel();
        if (StrUtil.isNotEmpty(resultDirFile)) {
            update.setResultDirFile(resultDirFile);
        }
        if (StrUtil.isNotEmpty(branchName)) {
            update.setBranchName(branchName);
        }
        if (StrUtil.isNotEmpty(branchTagName)) {
            update.setBranchTagName(branchTagName);
        }
        if (!StrUtil.isAllBlank(resultDirFile, branchName, branchTagName)) {
            update.setId(id);
            buildInfoService.update(update);
        }
        // userModel
        UserModel userModel = getUser();
        // ????????????
        return buildExecuteService.start(item.getId(), userModel, null, 0, buildRemark).toString();
    }

    /**
     * ????????????
     *
     * @param id id
     * @return json
     */
    @RequestMapping(value = "/build/manage/cancel", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.EXECUTE)
    public String cancel(@ValidatorConfig(@ValidatorItem(value = ValidatorRule.NOT_BLANK, msg = "????????????")) String id) {
        BuildInfoModel item = buildInfoService.getByKey(id, getRequest());
        Objects.requireNonNull(item, "??????????????????");
        BuildStatus nowStatus = BaseEnum.getEnum(BuildStatus.class, item.getStatus());
        Objects.requireNonNull(nowStatus);
        if (BuildStatus.Ing != nowStatus && BuildStatus.PubIng != nowStatus) {
            return JsonMessage.getString(501, "???????????????????????????");
        }
        boolean status = buildExecuteService.cancelTask(item.getId());
        if (!status) {
            // ??????????????????????????????,????????????????????????
            buildInfoService.updateStatus(id, BuildStatus.Cancel);
        }
        return JsonMessage.getString(200, "????????????");
    }

    /**
     * ????????????
     *
     * @param logId logId
     * @return json
     */
    @RequestMapping(value = "/build/manage/reRelease", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.EXECUTE)
    public String reRelease(@ValidatorConfig(@ValidatorItem(value = ValidatorRule.NOT_BLANK, msg = "????????????")) String logId) {
        BuildHistoryLog buildHistoryLog = dbBuildHistoryLogService.getByKey(logId, getRequest());
        Objects.requireNonNull(buildHistoryLog, "????????????????????????.");
        BuildInfoModel item = buildInfoService.getByKey(buildHistoryLog.getBuildDataId());
        Objects.requireNonNull(item, "??????????????????");
        String e = buildExecuteService.checkStatus(item.getStatus());
        Assert.isNull(e, () -> e);
        UserModel userModel = getUser();
        BuildExtraModule buildExtraModule = BuildExtraModule.build(buildHistoryLog);
        //new BuildExtraModule();
        //buildExtraModule.updateValue(buildHistoryLog);
        ReleaseManage manage = ReleaseManage.builder()
            .buildExtraModule(buildExtraModule)
            .logId(buildHistoryLog.getId())
            .userModel(userModel)
            .buildNumberId(buildHistoryLog.getBuildNumberId())
            .buildExecuteService(buildExecuteService)
            .build();
        //ReleaseManage releaseManage = new ReleaseManage(buildHistoryLog, userModel);
        // ???????????????
        //releaseManage.updateStatus(BuildStatus.PubIng);
        ThreadUtil.execute(manage);
        return JsonMessage.getString(200, "???????????????");
    }

    /**
     * ?????????????????????
     *
     * @param id      id
     * @param buildId ????????????
     * @param line    ?????????????????????
     * @return json
     */
    @RequestMapping(value = "/build/manage/get-now-log", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.LIST)
    public String getNowLog(@ValidatorItem(value = ValidatorRule.NOT_BLANK, msg = "????????????") String id,
                            @ValidatorItem(value = ValidatorRule.POSITIVE_INTEGER, msg = "??????buildId") int buildId,
                            @ValidatorItem(value = ValidatorRule.POSITIVE_INTEGER, msg = "line") int line) {
        BuildInfoModel item = buildInfoService.getByKey(id, getRequest());
        Assert.notNull(item, "??????????????????");
        Assert.state(buildId <= item.getBuildId(), "??????????????????????????????");

        BuildHistoryLog buildHistoryLog = new BuildHistoryLog();
        buildHistoryLog.setBuildDataId(id);
        buildHistoryLog.setBuildNumberId(buildId);
        BuildHistoryLog queryByBean = dbBuildHistoryLogService.queryByBean(buildHistoryLog);
        Assert.notNull(queryByBean, "???????????????????????????");

        File file = BuildUtil.getLogFile(item.getId(), buildId);
        Assert.state(FileUtil.isFile(file), "??????????????????");

        if (!file.exists()) {
            if (buildId == item.getBuildId()) {
                return JsonMessage.getString(201, "?????????????????????");
            }
            return JsonMessage.getString(300, "?????????????????????");
        }
        JSONObject data = FileUtils.readLogFile(file, line);
        // ?????????
        Integer status = queryByBean.getStatus();
        data.put("run", status == BuildStatus.Ing.getCode() || status == BuildStatus.PubIng.getCode());
        // ?????????
        data.put("buildRun", status == BuildStatus.Ing.getCode());

        return JsonMessage.getString(200, "ok", data);
    }
}
