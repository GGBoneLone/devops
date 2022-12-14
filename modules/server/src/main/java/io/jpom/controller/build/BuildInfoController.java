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

import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.RegexPool;
import cn.hutool.core.lang.Tuple;
import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.jiangzeyin.common.JsonMessage;
import cn.jiangzeyin.common.validator.ValidatorConfig;
import cn.jiangzeyin.common.validator.ValidatorItem;
import cn.jiangzeyin.common.validator.ValidatorRule;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import io.jpom.build.BuildExecuteService;
import io.jpom.build.BuildUtil;
import io.jpom.build.DockerYmlDsl;
import io.jpom.common.BaseServerController;
import io.jpom.model.AfterOpt;
import io.jpom.model.BaseEnum;
import io.jpom.model.PageResultDto;
import io.jpom.model.data.BuildInfoModel;
import io.jpom.model.data.RepositoryModel;
import io.jpom.model.data.SshModel;
import io.jpom.model.enums.BuildReleaseMethod;
import io.jpom.model.script.ScriptModel;
import io.jpom.permission.ClassFeature;
import io.jpom.permission.Feature;
import io.jpom.permission.MethodFeature;
import io.jpom.plugin.IPlugin;
import io.jpom.plugin.PluginFactory;
import io.jpom.service.dblog.BuildInfoService;
import io.jpom.service.dblog.DbBuildHistoryLogService;
import io.jpom.service.dblog.RepositoryService;
import io.jpom.service.docker.DockerInfoService;
import io.jpom.service.node.ssh.SshService;
import io.jpom.service.script.ScriptServer;
import io.jpom.system.extconf.BuildExtConfig;
import io.jpom.util.CommandUtil;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ????????????????????????????????????????????????????????????????????????
 * ?????????????????????????????????????????????????????????
 *
 * @author Hotstrip
 * @since 2021-08-09
 */
@RestController
@Feature(cls = ClassFeature.BUILD)
public class BuildInfoController extends BaseServerController {


    private final DbBuildHistoryLogService dbBuildHistoryLogService;
    private final SshService sshService;
    private final BuildInfoService buildInfoService;
    private final RepositoryService repositoryService;
    private final BuildExecuteService buildExecuteService;
    private final DockerInfoService dockerInfoService;
    private final ScriptServer scriptServer;
    private final BuildExtConfig buildExtConfig;

    public BuildInfoController(DbBuildHistoryLogService dbBuildHistoryLogService,
                               SshService sshService,
                               BuildInfoService buildInfoService,
                               RepositoryService repositoryService,
                               BuildExecuteService buildExecuteService,
                               DockerInfoService dockerInfoService,
                               ScriptServer scriptServer,
                               BuildExtConfig buildExtConfig) {
        this.dbBuildHistoryLogService = dbBuildHistoryLogService;
        this.sshService = sshService;
        this.buildInfoService = buildInfoService;
        this.repositoryService = repositoryService;
        this.buildExecuteService = buildExecuteService;
        this.dockerInfoService = dockerInfoService;
        this.scriptServer = scriptServer;
        this.buildExtConfig = buildExtConfig;
    }

    /**
     * load build list with params
     *
     * @return json
     */
    @RequestMapping(value = "/build/list", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.LIST)
    public String getBuildList() {
        // load list with page
        PageResultDto<BuildInfoModel> page = buildInfoService.listPage(getRequest());
        page.each(buildInfoModel -> {
            // ??????????????????????????????
            File source = BuildUtil.getSourceById(buildInfoModel.getId());
            buildInfoModel.setSourceDirExist(FileUtil.exist(source));
            //
            File file = BuildUtil.getHistoryPackageFile(buildInfoModel.getId(), buildInfoModel.getBuildId(), buildInfoModel.getResultDirFile());
            buildInfoModel.setResultHasFile(FileUtil.exist(file));
        });
        return JsonMessage.getString(200, "", page);
    }

    /**
     * load build list with params
     *
     * @return json
     */
    @GetMapping(value = "/build/list_all", produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.LIST)
    public String getBuildListAll() {
        // load list with page
        List<BuildInfoModel> modelList = buildInfoService.listByWorkspace(getRequest());
        return JsonMessage.getString(200, "", modelList);
    }

    /**
     * load build list with params
     *
     * @return json
     */
    @GetMapping(value = "/build/list_group_all", produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.LIST)
    public String getBuildGroupAll() {
        // load list with page
        List<String> group = buildInfoService.listGroup(getRequest());
        return JsonMessage.getString(200, "", group);
    }

    /**
     * edit build info
     *
     * @param id            ??????ID
     * @param name          ????????????
     * @param repositoryId  ??????ID
     * @param resultDirFile ??????????????????
     * @param script        ????????????
     * @param releaseMethod ????????????
     * @param branchName    ????????????
     * @param webhook       webhook
     * @param extraData     ?????????????????????
     * @param autoBuildCron ?????????????????????
     * @param branchTagName ?????????
     * @return json
     */
    @RequestMapping(value = "/build/edit", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.EDIT)
    public String updateBuild(String id,
                              @ValidatorItem(value = ValidatorRule.NOT_BLANK, msg = "????????????????????????") String name,
                              @ValidatorItem(value = ValidatorRule.NOT_BLANK, msg = "????????????????????????") String repositoryId,
                              @ValidatorItem(value = ValidatorRule.NOT_BLANK, msg = "??????????????????????????????,??????1-200", range = "1:200") String resultDirFile,
                              @ValidatorItem(value = ValidatorRule.NOT_BLANK, msg = "????????????????????????") String script,
                              @ValidatorItem(value = ValidatorRule.POSITIVE_INTEGER, msg = "?????????????????????") int releaseMethod,
                              String branchName, String branchTagName, String webhook, String autoBuildCron,
                              String extraData, String group,
                              @ValidatorItem(value = ValidatorRule.POSITIVE_INTEGER, msg = "?????????????????????") int buildMode) {
        // ?????? repositoryId ??????????????????
        RepositoryModel repositoryModel = repositoryService.getByKey(repositoryId, getRequest());
        Assert.notNull(repositoryModel, "?????????????????????");
        // ????????? GIT ??????????????????????????????
        if (RepositoryModel.RepoType.Git.getCode() == repositoryModel.getRepoType()) {
            Assert.hasText(branchName, "???????????????");
        } else if (RepositoryModel.RepoType.Svn.getCode() == repositoryModel.getRepoType()) {
            // ????????? SVN
            branchName = "trunk";
        }
        //
        Assert.state(buildMode == 0 || buildMode == 1, "??????????????????????????????");
        if (buildMode == 1) {
            // ?????? dsl ??????
            this.checkDocker(script);
        }
        if (buildExtConfig.checkDeleteCommand()) {
            // ??????????????????
            Assert.state(!CommandUtil.checkContainsDel(script), "????????????????????????");
        }
        // ??????????????????
        BuildInfoModel buildInfoModel = buildInfoService.getByKey(id, getRequest());
        buildInfoModel = ObjectUtil.defaultIfNull(buildInfoModel, new BuildInfoModel());
        // ????????????
        if (StrUtil.isNotEmpty(webhook)) {
            Validator.validateMatchRegex(RegexPool.URL_HTTP, webhook, "WebHooks ???????????????");
        }
        try {
            File userHomeDir = FileUtil.getUserHomeDir();
            FileUtil.checkSlip(userHomeDir, FileUtil.file(userHomeDir, resultDirFile));
        } catch (Exception e) {
            return JsonMessage.getString(405, "???????????????????????????" + e.getMessage());
        }
        buildInfoModel.setAutoBuildCron(this.checkCron(autoBuildCron));
        buildInfoModel.setWebhook(webhook);
        buildInfoModel.setRepositoryId(repositoryId);
        buildInfoModel.setName(name);
        buildInfoModel.setBranchName(branchName);
        buildInfoModel.setBranchTagName(branchTagName);
        buildInfoModel.setResultDirFile(resultDirFile);
        buildInfoModel.setScript(script);
        buildInfoModel.setGroup(group);
        buildInfoModel.setBuildMode(buildMode);
        // ????????????
        BuildReleaseMethod releaseMethod1 = BaseEnum.getEnum(BuildReleaseMethod.class, releaseMethod);
        Assert.notNull(releaseMethod1, "?????????????????????");
        buildInfoModel.setReleaseMethod(releaseMethod1.getCode());
        // ??? extraData ??????????????? JSON ?????????
        JSONObject jsonObject = JSON.parseObject(extraData);

        // ?????????????????? ??? extraData ??????
        if (releaseMethod1 == BuildReleaseMethod.Project) {
            this.formatProject(jsonObject);
        } else if (releaseMethod1 == BuildReleaseMethod.Ssh) {
            this.formatSsh(jsonObject);
        } else if (releaseMethod1 == BuildReleaseMethod.Outgiving) {
            String releaseMethodDataId = jsonObject.getString("releaseMethodDataId_1");
            Assert.hasText(releaseMethodDataId, "?????????????????????");
            jsonObject.put("releaseMethodDataId", releaseMethodDataId);
        } else if (releaseMethod1 == BuildReleaseMethod.LocalCommand) {
            this.formatLocalCommand(jsonObject);
            jsonObject.put("releaseMethodDataId", "LocalCommand");
        } else if (releaseMethod1 == BuildReleaseMethod.DockerImage) {
            // dockerSwarmId default
            String dockerSwarmId = this.formatDocker(jsonObject);
            jsonObject.put("releaseMethodDataId", dockerSwarmId);
        }
        // ??????????????????ID
        buildInfoModel.setReleaseMethodDataId(jsonObject.getString("releaseMethodDataId"));
        if (buildInfoModel.getReleaseMethod() != BuildReleaseMethod.No.getCode()) {
            Assert.hasText(buildInfoModel.getReleaseMethodDataId(), "????????????????????????????????????ID");
        }
        // ?????????????????????
        String noticeScriptId = jsonObject.getString("noticeScriptId");
        if (StrUtil.isNotEmpty(noticeScriptId)) {
            ScriptModel scriptModel = scriptServer.getByKey(noticeScriptId, getRequest());
            Assert.notNull(scriptModel, "?????????????????????????????????,???????????????");
        }
        buildInfoModel.setExtraData(jsonObject.toJSONString());

        // ??????????????????
        if (StrUtil.isEmpty(id)) {
            // set default buildId
            buildInfoModel.setBuildId(0);
            buildInfoService.insert(buildInfoModel);
            return JsonMessage.getString(200, "????????????");
        }

        buildInfoService.updateById(buildInfoModel, getRequest());
        return JsonMessage.getString(200, "????????????");
    }

    private void checkDocker(String script) {
        DockerYmlDsl build = DockerYmlDsl.build(script);
        build.check();
        //
        String fromTag = build.getFromTag();
        if (StrUtil.isNotEmpty(fromTag)) {
            //
            String workspaceId = dockerInfoService.getCheckUserWorkspace(getRequest());
            int count = dockerInfoService.countByTag(workspaceId, fromTag);
            Assert.state(count > 0, "docker tag ???????????????,??????????????????docker");
        }
    }

    /**
     * ??????????????????
     * ?????????????????????SSH????????????
     *
     * @param jsonObject ????????????
     */
    private void formatSsh(JSONObject jsonObject) {
        // ????????????
        String releaseMethodDataId = jsonObject.getString("releaseMethodDataId_3");
        Assert.hasText(releaseMethodDataId, "???????????????SSH???");

        String releasePath = jsonObject.getString("releasePath");
        Assert.hasText(releasePath, "??????????????????ssh????????????");
        releasePath = FileUtil.normalize(releasePath);
        String releaseCommand = jsonObject.getString("releaseCommand");
        List<String> strings = StrUtil.splitTrim(releaseMethodDataId, StrUtil.COMMA);
        for (String releaseMethodDataIdItem : strings) {
            SshModel sshServiceItem = sshService.getByKey(releaseMethodDataIdItem, getRequest());
            Assert.notNull(sshServiceItem, "???????????????ssh???");
            //
            if (releasePath.startsWith(StrUtil.SLASH)) {
                // ??????????????????
                List<String> fileDirs = sshServiceItem.fileDirs();
                Assert.notEmpty(fileDirs, sshServiceItem.getName() + "???ssh????????????????????????");

                boolean find = false;
                for (String fileDir : fileDirs) {
                    if (FileUtil.isSub(new File(fileDir), new File(releasePath))) {
                        find = true;
                    }
                }
                Assert.state(find, sshServiceItem.getName() + "???ssh????????????????????????");
            }
            // ????????????
            if (StrUtil.isNotEmpty(releaseCommand)) {
                int length = releaseCommand.length();
                Assert.state(length <= 4000, "???????????????????????????4000??????");
                //return JsonMessage.getString(405, "?????????????????????");
                String[] commands = StrUtil.splitToArray(releaseCommand, StrUtil.LF);

                for (String commandItem : commands) {
                    boolean checkInputItem = SshModel.checkInputItem(sshServiceItem, commandItem);
                    Assert.state(checkInputItem, sshServiceItem.getName() + "??????????????????????????????????????????");
                }
            }
        }
        jsonObject.put("releaseMethodDataId", releaseMethodDataId);
    }

    private String formatDocker(JSONObject jsonObject) {
        // ????????????
        String dockerfile = jsonObject.getString("dockerfile");
        Assert.hasText(dockerfile, "????????????????????? Dockerfile ??????");
        String fromTag = jsonObject.getString("fromTag");
        if (StrUtil.isNotEmpty(fromTag)) {
            Assert.hasText(fromTag, "??????????????? docker ??????");
            String workspaceId = dockerInfoService.getCheckUserWorkspace(getRequest());
            int count = dockerInfoService.countByTag(workspaceId, fromTag);
            Assert.state(count > 0, "docker tag ???????????????,??????????????????docker");
        }
        String dockerTag = jsonObject.getString("dockerTag");
        Assert.hasText(dockerTag, "?????????????????????");
        //
        String dockerSwarmId = jsonObject.getString("dockerSwarmId");
        if (StrUtil.isEmpty(dockerSwarmId)) {
            return "DockerImage";
        }
        String dockerSwarmServiceName = jsonObject.getString("dockerSwarmServiceName");
        Assert.hasText(dockerSwarmServiceName, "??????????????????????????????");
        return dockerSwarmId;
    }

    private void formatLocalCommand(JSONObject jsonObject) {
        // ????????????
        String releaseCommand = jsonObject.getString("releaseCommand");
        if (StrUtil.isNotEmpty(releaseCommand)) {
            int length = releaseCommand.length();
            Assert.state(length <= 4000, "???????????????????????????4000??????");
        }
    }

    /**
     * ??????????????????
     * ???????????????????????????????????????
     *
     * @param jsonObject ????????????
     */
    private void formatProject(JSONObject jsonObject) {
        String releaseMethodDataId2Node = jsonObject.getString("releaseMethodDataId_2_node");
        String releaseMethodDataId2Project = jsonObject.getString("releaseMethodDataId_2_project");

        Assert.state(!StrUtil.hasEmpty(releaseMethodDataId2Node, releaseMethodDataId2Project), "????????????????????????");
        jsonObject.put("releaseMethodDataId", String.format("%s:%s", releaseMethodDataId2Node, releaseMethodDataId2Project));
        //
        String afterOpt = jsonObject.getString("afterOpt");
        AfterOpt afterOpt1 = BaseEnum.getEnum(AfterOpt.class, Convert.toInt(afterOpt, 0));
        Assert.notNull(afterOpt1, "???????????????????????????");
        //
        String clearOld = jsonObject.getString("clearOld");
        jsonObject.put("afterOpt", afterOpt1.getCode());
        jsonObject.put("clearOld", Convert.toBool(clearOld, false));
    }

    /**
     * ??????????????????
     *
     * @param repositoryId ??????id
     * @return json
     * @throws Exception ??????
     */
    @RequestMapping(value = "/build/branch-list", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.LIST)
    public String branchList(
        @ValidatorConfig(@ValidatorItem(value = ValidatorRule.NOT_BLANK, msg = "??????ID????????????")) String repositoryId) throws Exception {
        // ?????? repositoryId ??????????????????
        RepositoryModel repositoryModel = repositoryService.getByKey(repositoryId, false);
        Assert.notNull(repositoryModel, "?????????????????????");
        //
        Assert.state(repositoryModel.getRepoType() == 0, "?????? GIT ????????????????????????");
        IPlugin plugin = PluginFactory.getPlugin("git-clone");
        Map<String, Object> map = repositoryModel.toMap();
        Tuple branchAndTagList = (Tuple) plugin.execute("branchAndTagList", map);
        Assert.notNull(branchAndTagList, "??????????????????");
        Object[] members = branchAndTagList.getMembers();
        return JsonMessage.getString(200, "ok", members);
    }


    /**
     * ??????????????????
     *
     * @param id ??????ID
     * @return json
     */
    @PostMapping(value = "/build/delete", produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.DEL)
    public String delete(@ValidatorItem(value = ValidatorRule.NOT_BLANK, msg = "????????????id") String id) {
        // ??????????????????
        HttpServletRequest request = getRequest();
        BuildInfoModel buildInfoModel = buildInfoService.getByKey(id, request);
        Objects.requireNonNull(buildInfoModel, "??????????????????");
        //
        String e = buildExecuteService.checkStatus(buildInfoModel.getStatus());
        Assert.isNull(e, () -> e);
        // ??????????????????
        dbBuildHistoryLogService.delByWorkspace(request, entity -> entity.set("buildDataId", buildInfoModel.getId()));
        // ????????????????????????
        File file = BuildUtil.getBuildDataFile(buildInfoModel.getId());
        // ????????????
        boolean fastDel = CommandUtil.systemFastDel(file);
        //
        Assert.state(!fastDel, "??????????????????????????????,??????????????????");
        // ????????????????????????
        buildInfoService.delByKey(buildInfoModel.getId(), request);
        return JsonMessage.getString(200, "??????????????????????????????");
    }


    /**
     * ??????????????????
     *
     * @param id ??????ID
     * @return json
     */
    @PostMapping(value = "/build/clean-source", produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.EXECUTE)
    public String cleanSource(@ValidatorItem(value = ValidatorRule.NOT_BLANK, msg = "????????????id") String id) {
        // ??????????????????
        BuildInfoModel buildInfoModel = buildInfoService.getByKey(id, getRequest());
        Objects.requireNonNull(buildInfoModel, "??????????????????");
        File source = BuildUtil.getSourceById(buildInfoModel.getId());
        // ????????????
        boolean fastDel = CommandUtil.systemFastDel(source);
        //
        Assert.state(!fastDel, "??????????????????,?????????");
        return JsonMessage.getString(200, "????????????");
    }

    /**
     * ??????
     *
     * @param id        ??????ID
     * @param method    ??????
     * @param compareId ?????????ID
     * @return msg
     */
    @GetMapping(value = "/build/sort-item", produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.EDIT)
    public JsonMessage<String> sortItem(@ValidatorItem String id, @ValidatorItem String method, String compareId) {
        HttpServletRequest request = getRequest();
        if (StrUtil.equalsIgnoreCase(method, "top")) {
            buildInfoService.sortToTop(id, request);
        } else if (StrUtil.equalsIgnoreCase(method, "up")) {
            buildInfoService.sortMoveUp(id, compareId, request);
        } else if (StrUtil.equalsIgnoreCase(method, "down")) {
            buildInfoService.sortMoveDown(id, compareId, request);
        } else {
            return new JsonMessage<>(400, "??????????????????" + method);
        }
        return new JsonMessage<>(200, "????????????");
    }

}
