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
package io.jpom;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.io.resource.Resource;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.AuthResponse;
import com.github.dockerjava.api.model.ResponseItem;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import io.jpom.plugin.IPlugin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ResourceUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * @author bwcx_jzy
 * @since 2022/1/26
 */
@Slf4j
public class DockerUtil {

    private static final Map<String, DockerClient> DOCKER_CLIENT_MAP = new ConcurrentHashMap<>();

    /**
     * ?????????????????? {@link org.springframework.boot.context.config.ConfigDataEnvironment}
     */
    public static final String[] FILE_PATHS = new String[]{System.getProperty(IPlugin.DATE_PATH_KEY) + File.separator, "file:./config/", "file:./"};

    /**
     * ?????????????????? dockerfile ??????
     */
    public static final String RUNS_FOLDER = "runs";
    /**
     * ????????????
     */
    public static final String DEFAULT_RUNS = "ubuntu-latest";
    /**
     * dockerfile ????????????
     */
    public static final String DOCKER_FILE = "Dockerfile";

    /**
     * ?????? docker client ??????????????????
     * <p>
     * ?????????????????? closeBefore ???????????????
     *
     * @param parameter ??????
     * @return DockerClient
     */
    public static DockerClient get(Map<String, Object> parameter) {
        String host = (String) parameter.get("dockerHost");
        String dockerCertPath = (String) parameter.get("dockerCertPath");
        String key = StrUtil.format("{}-{}", host, StrUtil.emptyToDefault(dockerCertPath, StrUtil.EMPTY));
        if (parameter.containsKey("closeBefore")) {
            //  ?????????????????????
            DockerClient dockerClient = DOCKER_CLIENT_MAP.remove(key);
            IoUtil.close(dockerClient);
        }
        return DOCKER_CLIENT_MAP.computeIfAbsent(key, s -> create(parameter));
    }

    /**
     * ?????? docker client ??????
     *
     * @param parameter ??????
     * @return DockerClient
     */
    private static DockerClient create(Map<String, Object> parameter) {
        String host = (String) parameter.get("dockerHost");
        String apiVersion = (String) parameter.get("apiVersion");
        String dockerCertPath = (String) parameter.get("dockerCertPath");
        String registryUsername = (String) parameter.get("registryUsername");
        String registryPassword = (String) parameter.get("registryPassword");
        String registryEmail = (String) parameter.get("registryEmail");
        String registryUrl = (String) parameter.get("registryUrl");
        //
        DefaultDockerClientConfig.Builder defaultConfigBuilder = DefaultDockerClientConfig.createDefaultConfigBuilder();
        defaultConfigBuilder
            .withDockerTlsVerify(StrUtil.isNotEmpty(dockerCertPath))
            .withApiVersion(apiVersion)
            .withDockerCertPath(dockerCertPath)
            .withDockerHost(host);
        //
        Opt.ofBlankAble(registryUrl).ifPresent(s -> defaultConfigBuilder.withRegistryUrl(registryUrl));
        Opt.ofBlankAble(registryEmail).ifPresent(s -> defaultConfigBuilder.withRegistryEmail(registryEmail));
        Opt.ofBlankAble(registryUsername).ifPresent(s -> defaultConfigBuilder.withRegistryUsername(registryUsername));
        Opt.ofBlankAble(registryPassword).ifPresent(s -> defaultConfigBuilder.withRegistryPassword(registryPassword));

        DockerClientConfig config = defaultConfigBuilder.build();
        //
        ApacheDockerHttpClient.Builder builder = new ApacheDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost())
            .sslConfig(config.getSSLConfig())
            .maxConnections(100);
        //
        int timeout = Convert.toInt(parameter.get("timeout"), 0);
        if (timeout > 0) {
            builder.connectionTimeout(Duration.ofSeconds(timeout));
            builder.responseTimeout(Duration.ofSeconds(timeout));
        }
        ApacheDockerHttpClient httpClient = builder.build();
        DockerClient dockerClient = DockerClientImpl.getInstance(config, httpClient);
        if (StrUtil.isNotEmpty(registryUrl)) {
            AuthConfig authConfig = dockerClient.authConfig();
            AuthResponse authResponse = dockerClient.authCmd().withAuthConfig(authConfig).exec();
            log.debug("auth cmd:{}", JSONObject.toJSONString(authResponse));
        }
        return dockerClient;
    }

    /**
     * ??????????????????
     *
     * @param name    ?????????
     * @param tempDir ??????????????????
     * @return temp
     */
    public static File createTemp(String name, File tempDir) {
        return FileUtil.file(tempDir, name);
    }

    /**
     * ????????????
     *
     * @param name    ????????????
     * @param tempDir ????????????
     * @return file ?????????????????????????????? null
     */
    public static File getResourceToFile(String name, File tempDir) {
        try {
            for (String filePath : FILE_PATHS) {
                File file;
                try {
                    file = ResourceUtils.getFile(filePath + name);
                    if (!file.exists()) {
                        log.debug("{} not found", filePath + name);
                        continue;
                    }
                } catch (FileNotFoundException e) {
                    log.debug("{} not found", filePath + name);
                    continue;
                }
                log.debug("found file:{}", file.getAbsolutePath());
                File tempFile = DockerUtil.createTemp(name, tempDir);
                Files.copy(file.toPath(), tempFile.toPath());
                return tempFile;
            }
            Resource resourceObj = ResourceUtil.getResourceObj(name);
            InputStream stream = resourceObj.getStream();
            File tempFile = DockerUtil.createTemp(name, tempDir);
            FileUtil.writeFromStream(stream, tempFile);
            return tempFile;
        } catch (Exception e) {
            log.error("?????? dockerfile ?????????????????? {}", name, e);
        }
        return null;
    }

    /**
     * ????????????
     *
     * @param name    ????????????
     * @param tempDir ????????????
     * @return path ?????????????????????????????? null
     */
    public static String getResourceToFilePath(String name, File tempDir) {
        File resourceToFile = getResourceToFile(name, tempDir);
        if (resourceToFile == null) {
            return null;
        }
        return resourceToFile.getAbsolutePath();
    }

    /**
     * ??????????????????
     *
     * @param responseItem ????????????
     * @return ????????? ?????????
     */
    public static String parseResponseItem(ResponseItem responseItem) {
        String stream = responseItem.getStream();
        if (stream == null) {
            String status = responseItem.getStatus();
            if (status == null) {
                Map<String, Object> rawValues = responseItem.getRawValues();
                return MapUtil.join(rawValues, ",", "=") + StrUtil.LF;
            }
            String progress = responseItem.getProgress();
            progress = StrUtil.emptyToDefault(progress, StrUtil.EMPTY);
            String id = responseItem.getId();
            id = StrUtil.emptyToDefault(id, StrUtil.EMPTY);
            return StrUtil.format("{} {} {}", status, id, progress);
        }
        return stream;
    }
}
