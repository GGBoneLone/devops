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
package io.jpom.build;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.BetweenFormatter;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.SystemClock;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.io.LineHandler;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.text.CharPool;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.extra.ssh.JschUtil;
import cn.hutool.extra.ssh.Sftp;
import cn.hutool.http.HttpStatus;
import cn.jiangzeyin.common.JsonMessage;
import cn.jiangzeyin.common.spring.SpringUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.jcraft.jsch.Session;
import io.jpom.common.forward.NodeForward;
import io.jpom.common.forward.NodeUrl;
import io.jpom.model.AfterOpt;
import io.jpom.model.BaseEnum;
import io.jpom.model.data.NodeModel;
import io.jpom.model.data.SshModel;
import io.jpom.model.docker.DockerInfoModel;
import io.jpom.model.enums.BuildReleaseMethod;
import io.jpom.model.enums.BuildStatus;
import io.jpom.model.user.UserModel;
import io.jpom.outgiving.OutGivingRun;
import io.jpom.plugin.IPlugin;
import io.jpom.plugin.PluginFactory;
import io.jpom.service.docker.DockerInfoService;
import io.jpom.service.docker.DockerSwarmInfoService;
import io.jpom.service.node.NodeService;
import io.jpom.service.node.ssh.SshService;
import io.jpom.service.system.WorkspaceEnvVarService;
import io.jpom.system.ConfigBean;
import io.jpom.system.JpomRuntimeException;
import io.jpom.util.CommandUtil;
import io.jpom.util.FileUtils;
import io.jpom.util.LogRecorder;
import io.jpom.util.StringUtil;
import lombok.Builder;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * ????????????
 *
 * @author bwcx_jzy
 * @since 2019/7/19
 */
@Builder
public class ReleaseManage implements Runnable {

    private final UserModel userModel;
    private final Integer buildNumberId;
    private final BuildExtraModule buildExtraModule;
    private final String logId;
    private final BuildExecuteService buildExecuteService;
    private final Map<String, String> buildEnv;

    private LogRecorder logRecorder;
    private File resultFile;

    private void init() {
        if (this.logRecorder == null) {
            File logFile = BuildUtil.getLogFile(buildExtraModule.getId(), buildNumberId);
            this.logRecorder = LogRecorder.builder().file(logFile).build();
        }
        this.resultFile = BuildUtil.getHistoryPackageFile(buildExtraModule.getId(), this.buildNumberId, buildExtraModule.getResultDirFile());
        //
//        envFileMap.put("BUILD_ID", this.buildExtraModule.getId());
//        envFileMap.put("BUILD_NAME", this.buildExtraModule.getName());
        buildEnv.put("BUILD_RESULT_FILE", FileUtil.getAbsolutePath(this.resultFile));
//        envFileMap.put("BUILD_NUMBER_ID", this.buildNumberId + StrUtil.EMPTY);
    }

//	/**
//	 * new ReleaseManage constructor
//	 *
//	 * @param buildModel ????????????
//	 * @param userModel  ????????????
//	 * @param baseBuild  ????????????
//	 * @param buildId    ????????????ID
//	 */
//	ReleaseManage(BuildExtraModule buildModel, UserModel userModel, int buildId) {
//
//
//		this.buildExtraModule = buildModel;
//		this.buildId = buildId;
//		this.userModel = userModel;
//
//	}

//	/**
//	 * ????????????
//	 *
//	 * @param buildHistoryLog ????????????
//	 * @param userModel       ??????
//	 */
//	public ReleaseManage(BuildHistoryLog buildHistoryLog, UserModel userModel) {
//		super(BuildUtil.getLogFile(buildHistoryLog.getBuildDataId(), buildHistoryLog.getBuildNumberId()),
//				buildHistoryLog.getBuildDataId());
//		this.buildExtraModule = new BuildExtraModule();
//		this.buildExtraModule.updateValue(buildHistoryLog);
//
//		this.buildId = buildHistoryLog.getBuildNumberId();
//		this.userModel = userModel;
//		this.resultFile = BuildUtil.getHistoryPackageFile(this.buildModelId, this.buildId, buildHistoryLog.getResultDirFile());
//	}


    public void updateStatus(BuildStatus status) {
        buildExecuteService.updateStatus(this.buildExtraModule.getId(), this.logId, status);
    }

    /**
     * ???????????????????????????
     */
    public void start() {
        init();
        updateStatus(BuildStatus.PubIng);
        logRecorder.info("start release???" + FileUtil.readableFileSize(FileUtil.size(this.resultFile)));
        if (!this.resultFile.exists()) {
            logRecorder.info("?????????????????????");
            updateStatus(BuildStatus.PubError);
            return;
        }
        long time = SystemClock.now();
        int releaseMethod = this.buildExtraModule.getReleaseMethod();
        logRecorder.info("release method:" + BaseEnum.getDescByCode(BuildReleaseMethod.class, releaseMethod));
        try {
            if (releaseMethod == BuildReleaseMethod.Outgiving.getCode()) {
                //
                this.doOutGiving();
            } else if (releaseMethod == BuildReleaseMethod.Project.getCode()) {
                this.doProject();
            } else if (releaseMethod == BuildReleaseMethod.Ssh.getCode()) {
                this.doSsh();
            } else if (releaseMethod == BuildReleaseMethod.LocalCommand.getCode()) {
                this.localCommand();
            } else if (releaseMethod == BuildReleaseMethod.DockerImage.getCode()) {
                this.doDockerImage();
            } else {
                logRecorder.info(" ???????????????????????????:" + releaseMethod);
            }
        } catch (Exception e) {
            this.pubLog("????????????", e);
            return;
        }
        logRecorder.info("release complete : " + DateUtil.formatBetween(SystemClock.now() - time, BetweenFormatter.Level.MILLISECOND));
        updateStatus(BuildStatus.PubSuccess);
    }


    /**
     * ?????????????????????
     *
     * @param commands ??????
     */
    private Map<String, String> formatCommand(String[] commands) {
        File sourceFile = BuildUtil.getSourceById(this.buildExtraModule.getId());
        File envFile = FileUtil.file(sourceFile, ".env");
        Map<String, String> envFileMap = FileUtils.readEnvFile(envFile);
        //
        envFileMap.putAll(buildEnv);
        //
        for (int i = 0; i < commands.length; i++) {
            commands[i] = StringUtil.formatStrByMap(commands[i], envFileMap);
        }
        //
        WorkspaceEnvVarService workspaceEnvVarService = SpringUtil.getBean(WorkspaceEnvVarService.class);
        workspaceEnvVarService.formatCommand(this.buildExtraModule.getWorkspaceId(), commands);
        return envFileMap;
    }

    private String parseDockerTag(File envFile, String tag) {
        if (!FileUtil.isFile(envFile)) {
            return tag;
        }
        final String[] newTag = {tag};
        FileUtil.readLines(envFile, StandardCharsets.UTF_8, (LineHandler) line -> {
            line = StrUtil.trim(line);
            if (StrUtil.startWith(line, "#")) {
                return;
            }
            List<String> list = StrUtil.splitTrim(line, "=");
            if (CollUtil.size(list) != 2) {
                return;
            }
            newTag[0] = StrUtil.replace(newTag[0], "${" + list.get(0) + "}", list.get(1));
        });
        return newTag[0];
    }

    /**
     * ???????????????
     *
     * @param dockerTagIncrement ???????????????????????????
     * @param dockerTag          ???????????????
     * @return ?????????????????????
     */
    private String dockerTagIncrement(Boolean dockerTagIncrement, String dockerTag) {
        if (dockerTagIncrement == null || !dockerTagIncrement) {
            return dockerTag;
        }
        List<String> list = StrUtil.splitTrim(dockerTag, StrUtil.COMMA);
        return list.stream().map(s -> {
            List<String> tag = StrUtil.splitTrim(s, StrUtil.COLON);
            String version = CollUtil.getLast(tag);
            List<String> versionList = StrUtil.splitTrim(version, StrUtil.DOT);
            int tagSize = CollUtil.size(tag);
            if (tagSize <= 1 || CollUtil.size(versionList) <= 1) {
                logRecorder.info("Warning version number incrementing error, no match for . or :");
                return s;
            }
            boolean match = false;
            for (int i = versionList.size() - 1; i >= 0; i--) {
                String versionParting = versionList.get(i);
                int versionPartingInt = Convert.toInt(versionParting, Integer.MIN_VALUE);
                if (versionPartingInt != Integer.MIN_VALUE) {
                    versionList.set(i, this.buildNumberId + StrUtil.EMPTY);
                    match = true;
                    break;
                }
            }
            tag.set(tagSize - 1, CollUtil.join(versionList, StrUtil.DOT));
            String newVersion = CollUtil.join(tag, StrUtil.COLON);
            if (match) {
                logRecorder.info("dockerTag version number incrementing {} -> {}", s, newVersion);
            } else {
                logRecorder.info("Warning version number incrementing error,No numeric version number {} ", s);
            }
            return newVersion;
        }).collect(Collectors.joining(StrUtil.COMMA));
    }

    private void doDockerImage() {
        // ??????????????????
        File tempPath = FileUtil.file(ConfigBean.getInstance().getTempPath(), "build_temp", "docker_image", this.buildExtraModule.getId() + StrUtil.DASHED + this.buildNumberId);
        try {
            File sourceFile = BuildUtil.getSourceById(this.buildExtraModule.getId());
            FileUtil.copyContent(sourceFile, tempPath, true);
            File historyPackageFile = BuildUtil.getHistoryPackageFile(buildExtraModule.getId(), this.buildNumberId, StrUtil.SLASH);
            FileUtil.copyContent(historyPackageFile, tempPath, true);
            // env file
            File envFile = FileUtil.file(tempPath, ".env");
            String dockerTag = this.buildExtraModule.getDockerTag();
            dockerTag = this.parseDockerTag(envFile, dockerTag);
            //
            dockerTag = this.dockerTagIncrement(this.buildExtraModule.getDockerTagIncrement(), dockerTag);
            // docker file
            String moduleDockerfile = this.buildExtraModule.getDockerfile();
            List<String> list = StrUtil.splitTrim(moduleDockerfile, StrUtil.COLON);
            String dockerFile = CollUtil.getLast(list);
            File dockerfile = FileUtil.file(tempPath, dockerFile);
            if (!FileUtil.isFile(dockerfile)) {
                logRecorder.info("??????????????????????????? Dockerfile ??????:", dockerFile);
                return;
            }
            File baseDir = FileUtil.file(tempPath, list.size() == 1 ? StrUtil.SLASH : CollUtil.get(list, 0));
            //
            String fromTag = this.buildExtraModule.getFromTag();
            // ?????? tag ??????
            List<DockerInfoModel> dockerInfoModels = buildExecuteService
                .dockerInfoService
                .queryByTag(this.buildExtraModule.getWorkspaceId(), 1, fromTag);
            DockerInfoModel dockerInfoModel = CollUtil.getFirst(dockerInfoModels);
            if (dockerInfoModel == null) {
                logRecorder.info("??????????????? docker server");
                return;
            }
            for (DockerInfoModel infoModel : dockerInfoModels) {
                this.doDockerImage(infoModel, dockerfile, baseDir, dockerTag);
            }
            // ??????
            Boolean pushToRepository = this.buildExtraModule.getPushToRepository();
            if (pushToRepository != null && pushToRepository) {
                List<String> repositoryList = StrUtil.splitTrim(dockerTag, StrUtil.COMMA);
                for (String repositoryItem : repositoryList) {
                    logRecorder.info("start push to repository in({}),{} {}", dockerInfoModel.getName(), StrUtil.emptyToDefault(dockerInfoModel.getRegistryUrl(), StrUtil.EMPTY), repositoryItem);
                    Map<String, Object> map = dockerInfoModel.toParameter();
                    //
                    map.put("repository", repositoryItem);
                    Consumer<String> logConsumer = s -> logRecorder.info(s);
                    map.put("logConsumer", logConsumer);
                    IPlugin plugin = PluginFactory.getPlugin(DockerInfoService.DOCKER_PLUGIN_NAME);
                    try {
                        plugin.execute("pushImage", map);
                    } catch (Exception e) {
                        logRecorder.error("??????????????????????????????", e);
                    }
                }
            }
            // ?????? docker ??????
            this.updateSwarmService(dockerTag, this.buildExtraModule.getDockerSwarmId(), this.buildExtraModule.getDockerSwarmServiceName());
        } finally {
            CommandUtil.systemFastDel(tempPath);
        }
    }

    private void updateSwarmService(String dockerTag, String swarmId, String serviceName) {
        if (StrUtil.isEmpty(swarmId)) {
            return;
        }
        List<String> splitTrim = StrUtil.splitTrim(dockerTag, StrUtil.COMMA);
        String first = CollUtil.getFirst(splitTrim);
        logRecorder.info("start update swarm service: {} use image {}", serviceName, first);
        Map<String, Object> pluginMap = buildExecuteService.dockerInfoService.getBySwarmPluginMap(swarmId);
        pluginMap.put("serviceId", serviceName);
        pluginMap.put("image", first);
        try {
            IPlugin plugin = PluginFactory.getPlugin(DockerSwarmInfoService.DOCKER_PLUGIN_NAME);
            plugin.execute("updateServiceImage", pluginMap);
        } catch (Exception e) {
            logRecorder.error("????????????????????????????????????", e);
        }
    }

    private void doDockerImage(DockerInfoModel dockerInfoModel, File dockerfile, File baseDir, String dockerTag) {
        logRecorder.info("{} start build image {}", dockerInfoModel.getName(), dockerTag);
        Map<String, Object> map = dockerInfoModel.toParameter();
        map.put("Dockerfile", dockerfile);
        map.put("baseDirectory", baseDir);
        //
        map.put("tags", dockerTag);
        Consumer<String> logConsumer = s -> logRecorder.append(s);
        map.put("logConsumer", logConsumer);
        IPlugin plugin = PluginFactory.getPlugin(DockerInfoService.DOCKER_PLUGIN_NAME);
        try {
            plugin.execute("buildImage", map);
        } catch (Exception e) {
            logRecorder.error("??????????????????????????????", e);
        }
    }

    /**
     * ??????????????????
     */
    private void localCommand() {
        // ????????????
        String[] commands = StrUtil.splitToArray(this.buildExtraModule.getReleaseCommand(), StrUtil.LF);
        if (ArrayUtil.isEmpty(commands)) {
            logRecorder.info("???????????????????????????");
            return;
        }
        String command = StrUtil.EMPTY;
        logRecorder.info(DateUtil.now() + " start exec");
        InputStream templateInputStream = null;
        try {
            templateInputStream = ResourceUtil.getStream("classpath:/bin/execTemplate." + CommandUtil.SUFFIX);
            if (templateInputStream == null) {
                logRecorder.info("???????????????????????????");
                return;
            }
            String sshExecTemplate = IoUtil.readUtf8(templateInputStream);
            StringBuilder stringBuilder = new StringBuilder(sshExecTemplate);
            // ????????????
            this.formatCommand(commands);
            //
            stringBuilder.append(ArrayUtil.join(commands, StrUtil.LF));
            File tempPath = ConfigBean.getInstance().getTempPath();
            File commandFile = FileUtil.file(tempPath, "build", this.buildExtraModule.getId() + StrUtil.DOT + CommandUtil.SUFFIX);
            FileUtil.writeUtf8String(stringBuilder.toString(), commandFile);
            //
            //			command = SystemUtil.getOsInfo().isWindows() ? StrUtil.EMPTY : CommandUtil.SUFFIX;
            command = CommandUtil.generateCommand(commandFile, "");
            //CommandUtil.EXECUTE_PREFIX + StrUtil.SPACE + FileUtil.getAbsolutePath(commandFile);
            String result = CommandUtil.execSystemCommand(command);
            logRecorder.info(result);
        } catch (Exception e) {
            this.pubLog("???????????????????????????" + command, e);
        } finally {
            IoUtil.close(templateInputStream);
        }
    }

    /**
     * ssh ??????
     */
    private void doSsh() {
        String releaseMethodDataId = this.buildExtraModule.getReleaseMethodDataId();
        SshService sshService = SpringUtil.getBean(SshService.class);
        List<String> strings = StrUtil.splitTrim(releaseMethodDataId, StrUtil.COMMA);
        for (String releaseMethodDataIdItem : strings) {
            SshModel item = sshService.getByKey(releaseMethodDataIdItem, false);
            if (item == null) {
                logRecorder.info("?????????????????????ssh??????" + releaseMethodDataIdItem);
                continue;
            }
            this.doSsh(item, sshService);
        }
    }

    private void doSsh(SshModel item, SshService sshService) {
        Session session = SshService.getSessionByModel(item);
        try {
            String releasePath = this.buildExtraModule.getReleasePath();
            if (StrUtil.isEmpty(releasePath)) {
                logRecorder.info("??????????????????");
            } else {
                logRecorder.info("{} {} start ftp upload", DateUtil.now(), item.getName());
                try (Sftp sftp = new Sftp(session, item.charset(), item.timeout())) {
                    String prefix = "";
                    if (!StrUtil.startWith(releasePath, StrUtil.SLASH)) {
                        prefix = sftp.pwd();
                    }
                    String normalizePath = FileUtil.normalize(prefix + StrUtil.SLASH + releasePath);
                    if (this.buildExtraModule.isClearOld()) {
                        try {
                            sftp.delDir(normalizePath);
                        } catch (Exception e) {
                            if (!StrUtil.startWithIgnoreCase(e.getMessage(), "No such file")) {
                                this.pubLog("????????????????????????", e);
                            }
                        }
                    }
                    sftp.syncUpload(this.resultFile, normalizePath);
                    logRecorder.info("{} ftp upload done", item.getName());
                }
            }
        } finally {
            JschUtil.close(session);
        }
        logRecorder.info("");
        // ????????????
        String[] commands = StrUtil.splitToArray(this.buildExtraModule.getReleaseCommand(), StrUtil.LF);
        if (commands == null || commands.length <= 0) {
            logRecorder.info("?????????????????????ssh??????");
            return;
        }
        // ????????????
        this.formatCommand(commands);
        //
        logRecorder.info("{} {} start exec", DateUtil.now(), item.getName());
        try {
            String s = sshService.exec(item, commands);
            logRecorder.info(s);
        } catch (Exception e) {
            this.pubLog(item.getName() + " ????????????", e);
        }
    }

    /**
     * ??????????????????
     *
     * @param nodeModel ??????
     * @param projectId ??????ID
     * @param afterOpt  ??????????????????
     */
    private void diffSyncProject(NodeModel nodeModel, String projectId, AfterOpt afterOpt, boolean clearOld) {
        File resultFile = this.resultFile;
        String resultFileParent = resultFile.isFile() ?
            FileUtil.getAbsolutePath(resultFile.getParent()) : FileUtil.getAbsolutePath(this.resultFile);
        //
        List<File> files = FileUtil.loopFiles(resultFile);
        List<JSONObject> collect = files.stream().map(file -> {
            //
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("name", StringUtil.delStartPath(file, resultFileParent, true));
            jsonObject.put("sha1", SecureUtil.sha1(file));
            return jsonObject;
        }).collect(Collectors.toList());
        //
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("id", projectId);
        jsonObject.put("data", collect);
        JsonMessage<JSONObject> requestBody = NodeForward.requestBody(nodeModel, NodeUrl.MANAGE_FILE_DIFF_FILE, this.userModel, jsonObject);
        if (requestBody.getCode() != HttpStatus.HTTP_OK) {
            throw new JpomRuntimeException("???????????????????????????" + requestBody);
        }
        JSONObject data = requestBody.getData();
        JSONArray diff = data.getJSONArray("diff");
        JSONArray del = data.getJSONArray("del");
        int delSize = CollUtil.size(del);
        int diffSize = CollUtil.size(diff);
        if (clearOld) {
            logRecorder.info(StrUtil.format("??????????????????,???????????? {} ?????????????????? {} ?????????????????? {} ???", CollUtil.size(collect), CollUtil.size(diff), delSize));
        } else {
            logRecorder.info(StrUtil.format("??????????????????,???????????? {} ?????????????????? {} ???", CollUtil.size(collect), CollUtil.size(diff)));
        }
        // ??????????????????????????????
        if (delSize > 0 && clearOld) {
            jsonObject.put("data", del);
            requestBody = NodeForward.requestBody(nodeModel, NodeUrl.MANAGE_FILE_BATCH_DELETE, this.userModel, jsonObject);
            if (requestBody.getCode() != HttpStatus.HTTP_OK) {
                throw new JpomRuntimeException("???????????????????????????" + requestBody);
            }
        }
        for (int i = 0; i < diffSize; i++) {
            boolean last = (i == diffSize - 1);
            JSONObject diffData = (JSONObject) diff.get(i);
            String name = diffData.getString("name");
            File file = FileUtil.file(resultFileParent, name);
            //
            String startPath = StringUtil.delStartPath(file, resultFileParent, false);
            //
            JsonMessage<String> jsonMessage = OutGivingRun.fileUpload(file, startPath,
                projectId, false, last ? afterOpt : AfterOpt.No, nodeModel, this.userModel, false);
            if (jsonMessage.getCode() != HttpStatus.HTTP_OK) {
                throw new JpomRuntimeException("???????????????????????????" + jsonMessage);
            }
            if (last) {
                // ????????????
                logRecorder.info("????????????????????????" + jsonMessage);
            }
        }
    }

    /**
     * ????????????
     */
    private void doProject() {
//		AfterOpt afterOpt, boolean clearOld, boolean diffSync
        AfterOpt afterOpt = BaseEnum.getEnum(AfterOpt.class, this.buildExtraModule.getAfterOpt(), AfterOpt.No);
        boolean clearOld = this.buildExtraModule.isClearOld();
        boolean diffSync = this.buildExtraModule.isDiffSync();
        String releaseMethodDataId = this.buildExtraModule.getReleaseMethodDataId();
        String[] strings = StrUtil.splitToArray(releaseMethodDataId, CharPool.COLON);
        if (ArrayUtil.length(strings) != 2) {
            throw new IllegalArgumentException(releaseMethodDataId + " error");
        }
        NodeService nodeService = SpringUtil.getBean(NodeService.class);
        NodeModel nodeModel = nodeService.getByKey(strings[0]);
        Objects.requireNonNull(nodeModel, "???????????????");
        String projectId = strings[1];
        if (diffSync) {
            this.diffSyncProject(nodeModel, projectId, afterOpt, clearOld);
            return;
        }
        File zipFile = BuildUtil.isDirPackage(this.resultFile);
        boolean unZip = true;
        if (zipFile == null) {
            zipFile = this.resultFile;
            unZip = false;
        }
        JsonMessage<String> jsonMessage = OutGivingRun.fileUpload(zipFile, null,
            projectId,
            unZip,
            afterOpt,
            nodeModel, this.userModel, clearOld);
        if (jsonMessage.getCode() == HttpStatus.HTTP_OK) {
            logRecorder.info("????????????????????????" + jsonMessage);
        } else {
            throw new JpomRuntimeException("????????????????????????" + jsonMessage);
        }
    }

    /**
     * ?????????
     */
    private void doOutGiving() {
        String releaseMethodDataId = this.buildExtraModule.getReleaseMethodDataId();
        File zipFile = BuildUtil.isDirPackage(this.resultFile);
        boolean unZip = true;
        if (zipFile == null) {
            zipFile = this.resultFile;
            unZip = false;
        }
        OutGivingRun.startRun(releaseMethodDataId, zipFile, userModel, unZip);
        logRecorder.info("????????????????????????,?????????????????????????????????");
    }


    /**
     * ??????????????????
     *
     * @param title     ??????
     * @param throwable ??????
     */
    private void pubLog(String title, Throwable throwable) {
        logRecorder.error(title, throwable);
        this.updateStatus(BuildStatus.PubError);
    }

    @Override
    public void run() {
        this.start();
    }
}
