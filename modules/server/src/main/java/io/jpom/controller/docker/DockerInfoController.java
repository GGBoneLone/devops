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
package io.jpom.controller.docker;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.db.Entity;
import cn.jiangzeyin.common.JsonMessage;
import cn.jiangzeyin.common.validator.ValidatorItem;
import cn.jiangzeyin.controller.multipart.MultipartFileBuilder;
import com.alibaba.fastjson.JSONObject;
import io.jpom.common.BaseServerController;
import io.jpom.model.PageResultDto;
import io.jpom.model.docker.DockerInfoModel;
import io.jpom.model.docker.DockerSwarmInfoMode;
import io.jpom.permission.ClassFeature;
import io.jpom.permission.Feature;
import io.jpom.permission.MethodFeature;
import io.jpom.plugin.IPlugin;
import io.jpom.plugin.PluginFactory;
import io.jpom.service.docker.DockerInfoService;
import io.jpom.service.docker.DockerSwarmInfoService;
import io.jpom.system.ServerConfigBean;
import io.jpom.util.CompressionFileUtil;
import io.jpom.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author bwcx_jzy
 * @since 2022/1/26
 */
@RestController
@Feature(cls = ClassFeature.DOCKER)
@RequestMapping(value = "/docker")
@Slf4j
public class DockerInfoController extends BaseServerController {

    private final DockerInfoService dockerInfoService;
    private final DockerSwarmInfoService dockerSwarmInfoService;

    public DockerInfoController(DockerInfoService dockerInfoService,
                                DockerSwarmInfoService dockerSwarmInfoService) {
        this.dockerInfoService = dockerInfoService;
        this.dockerSwarmInfoService = dockerSwarmInfoService;
    }

    /**
     * @return json
     */
    @GetMapping(value = "api-versions", produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.LIST)
    public String apiVersions() throws Exception {
        IPlugin plugin = PluginFactory.getPlugin(DockerInfoService.DOCKER_CHECK_PLUGIN_NAME);
        List<JSONObject> data = (List<JSONObject>) plugin.execute("apiVersions");
        return JsonMessage.getString(200, "", data);
    }

    /**
     * @return json
     */
    @PostMapping(value = "list", produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.LIST)
    public String list() {
        // load list with page
        PageResultDto<DockerInfoModel> resultDto = dockerInfoService.listPage(getRequest());
        resultDto.each(this::checkCertPath);
        return JsonMessage.getString(200, "", resultDto);
    }

    /**
     * ?????? ????????????????????????
     *
     * @param dockerInfoModel docker
     * @return true ??????????????????
     */
    private boolean checkCertPath(DockerInfoModel dockerInfoModel) {
        if (dockerInfoModel == null) {
            return false;
        }
        if (dockerInfoModel.getCertExist() != null && dockerInfoModel.getCertExist()) {
            return true;
        }
        String certPath = dockerInfoModel.generateCertPath();
        IPlugin plugin = PluginFactory.getPlugin(DockerInfoService.DOCKER_CHECK_PLUGIN_NAME);
        try {
            boolean execute = (boolean) plugin.execute("certPath", "certPath", certPath);
            dockerInfoModel.setCertExist(execute);
            return execute;
        } catch (Exception e) {
            log.error("?????? docker ????????????", e);
            return false;
        }
    }

    /**
     * ??????????????????
     *
     * @param certPathFile ???????????????????????????
     * @return model
     * @throws Exception ??????
     */
    private DockerInfoModel takeOverModel(File certPathFile) throws Exception {
        IPlugin plugin = PluginFactory.getPlugin(DockerInfoService.DOCKER_CHECK_PLUGIN_NAME);
        String name = getParameter("name");
        Assert.hasText(name, "????????? ??????");
        String host = getParameter("host");
        String id = getParameter("id");
        String tlsVerifyStr = getParameter("tlsVerify");
        String apiVersion = getParameter("apiVersion");
        String tagsStr = getParameter("tags", StrUtil.EMPTY);
        String registryUrl = getParameter("registryUrl");
        String registryUsername = getParameter("registryUsername");
        String registryPassword = getParameter("registryPassword");
        String registryEmail = getParameter("registryEmail");
        int heartbeatTimeout = getParameterInt("heartbeatTimeout", -1);
        boolean tlsVerify = Convert.toBool(tlsVerifyStr, false);
        //
        boolean certExist = false;
        if (tlsVerify) {
            // ????????????????????????????????????
            MultipartFileBuilder multipart = null;
            try {
                multipart = createMultipart();
            } catch (Exception e) {
                DockerInfoModel dockerInfoModel = dockerInfoService.getByKey(id);
                certExist = this.checkCertPath(dockerInfoModel);
                Assert.state(certExist, "?????????????????????");
            }
            if (multipart != null) {
                String absolutePath = FileUtil.getAbsolutePath(certPathFile);
                multipart.setSavePath(absolutePath).addFieldName("file").setUseOriginalFilename(true);
                String localPath = multipart.setFileExt(StringUtil.PACKAGE_EXT).save();
                // ??????
                File file = new File(localPath);
                CompressionFileUtil.unCompress(file, certPathFile);
                boolean ok = (boolean) plugin.execute("certPath", "certPath", absolutePath);
                Assert.state(ok, "?????????????????????,????????????????????????????????????ca.pem???key.pem???cert.pem");
                certExist = true;
            }
        }
        boolean ok = (boolean) plugin.execute("host", "host", host);
        Assert.state(ok, "?????????????????? host");
        //
        List<String> tagList = StrUtil.splitTrim(tagsStr, StrUtil.COMMA);
        String newTags = CollUtil.join(tagList, StrUtil.COLON, StrUtil.COLON, StrUtil.COLON);
        // ????????????
        String workspaceId = dockerInfoService.getCheckUserWorkspace(getRequest());
        Entity entity = Entity.create();
        entity.set("host", host);
        entity.set("workspaceId", workspaceId);
        if (StrUtil.isNotEmpty(id)) {
            entity.set("id", StrUtil.format(" <> {}", id));
        }
        boolean exists = dockerInfoService.exists(entity);
        Assert.state(!exists, "????????? docker ???????????????");
        //
        DockerInfoModel.DockerInfoModelBuilder builder = DockerInfoModel.builder();
        builder.heartbeatTimeout(heartbeatTimeout).apiVersion(apiVersion).host(host).name(name)
            .tlsVerify(tlsVerify).certExist(certExist).tags(newTags)
            .registryUrl(registryUrl).registryUsername(registryUsername).registryPassword(registryPassword).registryEmail(registryEmail);
        //
        DockerInfoModel build = builder.build();
        build.setId(id);
        return build;
    }

    @PostMapping(value = "edit", produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.EDIT)
    public String edit(String id, String host) throws Exception {
        // ????????????
        File tempPath = ServerConfigBean.getInstance().getUserTempPath();
        File savePath = FileUtil.file(tempPath, "docker", SecureUtil.sha1(host));
        DockerInfoModel dockerInfoModel = this.takeOverModel(savePath);
        boolean certExist = dockerInfoModel.getCertExist();
        if (StrUtil.isEmpty(id)) {
            // ??????
            if (dockerInfoModel.getTlsVerify()) {
                Assert.state(certExist, "?????????????????????");
            }
            this.check(dockerInfoModel, certExist, savePath);
            // ????????????
            dockerInfoModel.setStatus(1);
            dockerInfoService.insert(dockerInfoModel);
        } else {
            this.check(dockerInfoModel, certExist, savePath);
            dockerInfoService.updateById(dockerInfoModel, getRequest());
        }
        //
        return JsonMessage.getString(200, "????????????");
    }

    private void check(DockerInfoModel dockerInfoModel, boolean certExist, File savePath) throws Exception {
        // ????????????
        if (certExist) {
            if (FileUtil.isDirectory(savePath) && FileUtil.isNotEmpty(savePath)) {
                String generateCertPath = dockerInfoModel.generateCertPath();
                FileUtil.moveContent(savePath, FileUtil.file(generateCertPath), true);
            }
        }
        IPlugin plugin = PluginFactory.getPlugin(DockerInfoService.DOCKER_CHECK_PLUGIN_NAME);
        Map<String, Object> parameter = dockerInfoModel.toParameter();
        parameter.put("closeBefore", true);
        boolean ok = (boolean) plugin.execute("ping", parameter);
        Assert.state(ok, "???????????? docker ????????? host ?????? TLS ??????");
        // ????????????
        String registryUrl = dockerInfoModel.getRegistryUrl();
        if (StrUtil.isNotEmpty(registryUrl)) {
            DockerInfoModel oldInfoModel = dockerInfoService.getByKey(dockerInfoModel.getId(), false);
            String registryPassword = Optional.ofNullable(dockerInfoModel.getRegistryPassword()).orElseGet(() -> Optional.ofNullable(oldInfoModel).map(DockerInfoModel::getRegistryPassword).orElse(null));
            Assert.hasText(registryPassword, "????????????????????????");
            parameter.put("closeBefore", true);
            parameter.put("registryPassword", registryPassword);
            try {
                JSONObject jsonObject = (JSONObject) plugin.execute("testAuth", parameter);
                log.info("{}", jsonObject);
            } catch (Exception e) {
                log.warn("????????????????????????", e);
                throw new IllegalArgumentException("?????????????????????????????????" + e.getMessage());
            }
        }
    }


    @GetMapping(value = "del", produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.DEL)
    public String del(@ValidatorItem String id) throws Exception {
        DockerInfoModel infoModel = dockerInfoService.getByKey(id, getRequest());
        if (infoModel != null) {
            // ??????????????????????????? docker ????????????????????????
            DockerInfoModel dockerInfoModel = new DockerInfoModel();
            dockerInfoModel.setHost(infoModel.getHost());
            long count = dockerInfoService.count(dockerInfoService.dataBeanToEntity(dockerInfoModel));
            if (count <= 1) {
                // ????????????
                FileUtil.del(infoModel.generateCertPath());
            }
            dockerInfoService.delByKey(id);
        }
        return JsonMessage.getString(200, "????????????");
    }

    @GetMapping(value = "info", produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.LIST)
    public String info(@ValidatorItem String id) throws Exception {
        DockerInfoModel dockerInfoModel = dockerInfoService.getByKey(id, getRequest());
        IPlugin plugin = PluginFactory.getPlugin(DockerInfoService.DOCKER_CHECK_PLUGIN_NAME);
        JSONObject info = plugin.execute("info", dockerInfoModel.toParameter(), JSONObject.class);
        return JsonMessage.getString(200, "", info);
    }

    /**
     * ??????????????????
     *
     * @param id ??????ID
     * @return json
     */
    @GetMapping(value = "swarm-leave-force", produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.DEL)
    public JsonMessage<String> leaveForce(@ValidatorItem String id) throws Exception {
        //
        DockerSwarmInfoMode dockerSwarmInfoMode = new DockerSwarmInfoMode();
        dockerSwarmInfoMode.setDockerId(id);
        long count = dockerSwarmInfoService.count(dockerSwarmInfoMode);
        Assert.state(count <= 0, "?????????????????????????????????????????????");
        //
        DockerInfoModel dockerInfoModel = dockerInfoService.getByKey(id, getRequest());
        IPlugin plugin = PluginFactory.getPlugin(DockerSwarmInfoService.DOCKER_PLUGIN_NAME);
        Map<String, Object> parameter = dockerInfoModel.toParameter();
        parameter.put("force", true);
        plugin.execute("leaveSwarm", parameter, JSONObject.class);
        //
        dockerInfoService.unbind(id);
        return new JsonMessage<>(200, "??????????????????");
    }
}
