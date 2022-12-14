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
import cn.hutool.core.io.IORuntimeException;
import cn.hutool.core.lang.Tuple;
import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.db.Entity;
import cn.hutool.db.Page;
import cn.hutool.extra.servlet.ServletUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.jiangzeyin.common.JsonMessage;
import cn.jiangzeyin.common.validator.ValidatorItem;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.jpom.build.BuildUtil;
import io.jpom.common.BaseServerController;
import io.jpom.common.ServerConst;
import io.jpom.model.PageResultDto;
import io.jpom.model.data.RepositoryModel;
import io.jpom.model.enums.GitProtocolEnum;
import io.jpom.permission.ClassFeature;
import io.jpom.permission.Feature;
import io.jpom.permission.MethodFeature;
import io.jpom.plugin.IPlugin;
import io.jpom.plugin.PluginFactory;
import io.jpom.service.dblog.BuildInfoService;
import io.jpom.service.dblog.RepositoryService;
import io.jpom.system.JpomRuntimeException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Repository controller
 *
 * @author Hotstrip
 */
@RestController
@Feature(cls = ClassFeature.BUILD_REPOSITORY)
@Slf4j
public class RepositoryController extends BaseServerController {

    private final RepositoryService repositoryService;
    private final BuildInfoService buildInfoService;

    public RepositoryController(RepositoryService repositoryService,
                                BuildInfoService buildInfoService) {
        this.repositoryService = repositoryService;
        this.buildInfoService = buildInfoService;
    }

    /**
     * load repository list
     *
     * <pre>
     *     ???????????????????????????????????????????????????????????????????????????{@link #loadRepositoryListAll()}
     * </pre>
     *
     * @return json
     */
    @PostMapping(value = "/build/repository/list")
    @Feature(method = MethodFeature.LIST)
    public Object loadRepositoryList() {
        PageResultDto<RepositoryModel> pageResult = repositoryService.listPage(getRequest());
        return JsonMessage.getString(200, "????????????", pageResult);
    }

    /**
     * load repository list
     *
     * <pre>
     *     ??????????????????????????????????????????????????????????????????{@link #loadRepositoryList()}
     * </pre>
     *
     * @return json
     */
    @GetMapping(value = "/build/repository/list_all")
    @Feature(method = MethodFeature.LIST)
    public Object loadRepositoryListAll() {
        List<RepositoryModel> repositoryModels = repositoryService.listByWorkspace(getRequest());
        return JsonMessage.getString(200, "", repositoryModels);
    }

    /**
     * edit
     *
     * @param repositoryModelReq ????????????
     * @return json
     */
    @PostMapping(value = "/build/repository/edit")
    @Feature(method = MethodFeature.EDIT)
    public Object editRepository(RepositoryModel repositoryModelReq) {
        this.checkInfo(repositoryModelReq);
        // ?????? rsa ??????
        boolean andUpdateSshKey = this.checkAndUpdateSshKey(repositoryModelReq);
        Assert.state(andUpdateSshKey, "rsa ?????????????????????????????????");

        if (repositoryModelReq.getRepoType() == RepositoryModel.RepoType.Git.getCode()) {
            RepositoryModel repositoryModel = repositoryService.getByKey(repositoryModelReq.getId(), false);
            if (repositoryModel != null) {
                repositoryModelReq.setRsaPrv(StrUtil.emptyToDefault(repositoryModelReq.getRsaPrv(), repositoryModel.getRsaPrv()));
                repositoryModelReq.setPassword(StrUtil.emptyToDefault(repositoryModelReq.getPassword(), repositoryModel.getPassword()));
            }
            // ?????? git ????????????
            try {
                IPlugin plugin = PluginFactory.getPlugin("git-clone");
                Map<String, Object> map = repositoryModelReq.toMap();
                Tuple branchAndTagList = (Tuple) plugin.execute("branchAndTagList", map);
                //Tuple tuple = GitUtil.getBranchAndTagList(repositoryModelReq);
            } catch (JpomRuntimeException jpomRuntimeException) {
                throw jpomRuntimeException;
            } catch (Exception e) {
                log.warn("????????????????????????", e);
                return JsonMessage.toJson(500, "????????????????????????" + e.getMessage());
            }
        }
        if (StrUtil.isEmpty(repositoryModelReq.getId())) {
            // insert data
            repositoryService.insert(repositoryModelReq);
        } else {
            // update data
            //repositoryModelReq.setWorkspaceId(repositoryService.getCheckUserWorkspace(getRequest()));
            repositoryService.updateById(repositoryModelReq, getRequest());
        }

        return JsonMessage.toJson(200, "????????????");
    }

    /**
     * edit
     *
     * @param id ????????????
     * @return json
     */
    @PostMapping(value = "/build/repository/rest_hide_field")
    @Feature(method = MethodFeature.EDIT)
    public Object restHideField(@ValidatorItem String id) {
        RepositoryModel repositoryModel = new RepositoryModel();
        repositoryModel.setId(id);
        repositoryModel.setPassword(StrUtil.EMPTY);
        repositoryModel.setRsaPrv(StrUtil.EMPTY);
        repositoryModel.setRsaPub(StrUtil.EMPTY);
        repositoryModel.setWorkspaceId(repositoryService.getCheckUserWorkspace(getRequest()));
        repositoryService.updateById(repositoryModel);
        return JsonMessage.toJson(200, "????????????");
    }

    @GetMapping(value = "/build/repository/authorize_repos")
    @Feature(method = MethodFeature.LIST)
    public Object authorizeRepos() {
        // ??????????????????
        HttpServletRequest request = getRequest();
        Map<String, String> paramMap = ServletUtil.getParamMap(request);
        Page page = repositoryService.parsePage(paramMap);
        String token = paramMap.get("token");
        Assert.hasText(token, "?????????????????????");
        String gitlabAddress = StrUtil.blankToDefault(paramMap.get("gitlabAddress"), "https://gitlab.com");
        // ????????????
        String condition = paramMap.get("condition");
        // ????????????
        String type = paramMap.get("type");
        PageResultDto<JSONObject> pageResultDto;
        switch (type) {
            case "gitee":
                pageResultDto = this.giteeRepos(token, page, condition);
                break;
            case "github":
                // GitHub ?????????????????????
                pageResultDto = this.githubRepos(token, page);
                break;
            case "gitlab":
                pageResultDto = this.gitlabRepos(token, page, condition, gitlabAddress);
                break;
            default:
                throw new IllegalArgumentException("??????????????????");
        }
        return JsonMessage.toJson(HttpStatus.OK.value(), HttpStatus.OK.name(), pageResultDto);
    }

    /**
     * gitlab ??????
     * <p>
     * https://docs.gitlab.com/ee/api/projects.html#list-all-projects
     *
     * @param token         ????????????
     * @param page          ??????
     * @param gitlabAddress gitLab ??????
     * @return page
     */
    private PageResultDto<JSONObject> gitlabRepos(String token, Page page, String condition, String gitlabAddress) {
        // ??????????????? /
        if (gitlabAddress.endsWith("/")) {
            gitlabAddress = gitlabAddress.substring(0, gitlabAddress.length() - 1);
        }

        // ???????????? GitLab???????????????????????? https ??????????????????????????????????????????????????? http???gitlab ??? https
        // https ??????????????????????????????????????? http
        if (!StrUtil.startWithAnyIgnoreCase(gitlabAddress, "http://", "https://")) {
            gitlabAddress = "http://" + gitlabAddress;
        }

        HttpResponse userResponse = null;
        try {
            userResponse = GitLabUtil.getGitLabUserInfo(gitlabAddress, token);
        } catch (IORuntimeException ioRuntimeException) {
            // ???????????????????????? http ??????????????????
            if (StrUtil.startWithIgnoreCase(gitlabAddress, "https")) {
                gitlabAddress = "http" + gitlabAddress.substring(5);
                userResponse = GitLabUtil.getGitLabUserInfo(gitlabAddress, token);
            }
            Assert.state(userResponse != null, "??????????????? GitLab???" + ioRuntimeException.getMessage());
        }

        Assert.state(userResponse.isOk(), "??????????????????" + userResponse.body());
        JSONObject userBody = JSONObject.parseObject(userResponse.body());
        String username = userBody.getString("username");

        Map<String, Object> gitLabRepos = GitLabUtil.getGitLabRepos(gitlabAddress, token, page, condition);

        JSONArray jsonArray = JSONArray.parseArray((String) gitLabRepos.get("body"));
        List<JSONObject> objects = jsonArray.stream().map(o -> {
            JSONObject repo = (JSONObject) o;
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("name", repo.getString("name"));
            String htmlUrl = repo.getString("http_url_to_repo");
            jsonObject.put("url", htmlUrl);
            jsonObject.put("full_name", repo.getString("path_with_namespace"));
            // visibility ????????????public, internal, or private.?????? public????????? private???
            jsonObject.put("private", !StrUtil.equalsIgnoreCase("public", repo.getString("visibility")));
            jsonObject.put("description", repo.getString("description"));
            jsonObject.put("username", username);
            jsonObject.put("exists", RepositoryController.this.checkRepositoryUrl(htmlUrl));
            return jsonObject;
        }).collect(Collectors.toList());

        PageResultDto<JSONObject> pageResultDto = new PageResultDto<>(page.getPageNumber(), page.getPageSize(), (int) gitLabRepos.get("total"));
        pageResultDto.setResult(objects);
        return pageResultDto;
    }

    /**
     * github ??????
     *
     * @param token ????????????
     * @param page  ??????
     * @return page
     */
    private PageResultDto<JSONObject> githubRepos(String token, Page page) {
        GitHubUtil.GitHubUserInfo gitHubUserInfo = GitHubUtil.getGitHubUserInfo(token);
        JSONArray gitHubUserReposArray = GitHubUtil.getGitHubUserRepos(token, page);

        List<JSONObject> objects = gitHubUserReposArray.stream().map(o -> {
            JSONObject repo = (JSONObject) o;
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("name", repo.getString("name"));
            String cloneUrl = repo.getString("clone_url");
            jsonObject.put("url", cloneUrl);
            jsonObject.put("full_name", repo.getString("full_name"));
            jsonObject.put("description", repo.getString("description"));
            jsonObject.put("private", repo.getBooleanValue("private"));
            //
            jsonObject.put("username", gitHubUserInfo.getLogin());
            jsonObject.put("exists", RepositoryController.this.checkRepositoryUrl(cloneUrl));
            return jsonObject;
        }).collect(Collectors.toList());
        //
        PageResultDto<JSONObject> pageResultDto = new PageResultDto<>(page.getPageNumber(), page.getPageSize(), gitHubUserInfo.public_repos);
        pageResultDto.setResult(objects);
        return pageResultDto;
    }

    /**
     * gitee ??????
     *
     * @param token ????????????
     * @param page  ??????
     * @return page
     */
    private PageResultDto<JSONObject> giteeRepos(String token, Page page, String condition) {
        String giteeUsername = GiteeUtil.getGiteeUsername(token);

        Map<String, Object> giteeReposMap = GiteeUtil.getGiteeRepos(token, page, condition);
        JSONArray jsonArray = (JSONArray) giteeReposMap.get("jsonArray");
        int totalCount = (int) giteeReposMap.get("totalCount");

        List<JSONObject> objects = jsonArray.stream().map(o -> {
            JSONObject repo = (JSONObject) o;
            JSONObject jsonObject = new JSONObject();
            // ?????????????????????Jpom
            jsonObject.put("name", repo.getString("name"));

            // ?????????????????????https://gitee.com/dromara/Jpom.git
            String htmlUrl = repo.getString("html_url");
            jsonObject.put("url", htmlUrl);

            // ?????????/??????????????????dromara/Jpom
            jsonObject.put("full_name", repo.getString("full_name"));

            // ?????????????????????????????????????????? true????????????????????? false
            jsonObject.put("private", repo.getBooleanValue("private"));

            // ????????????????????????????????????????????????????????????????????????????????????????????????????????????
            jsonObject.put("description", repo.getString("description"));

            jsonObject.put("username", giteeUsername);
            jsonObject.put("exists", this.checkRepositoryUrl(htmlUrl));
            return jsonObject;
        }).collect(Collectors.toList());

        PageResultDto<JSONObject> pageResultDto = new PageResultDto<>(page.getPageNumber(), page.getPageSize(), totalCount);
        pageResultDto.setResult(objects);
        return pageResultDto;
    }

    /**
     * ????????????
     *
     * @param repositoryModelReq ????????????
     */
    private void checkInfo(RepositoryModel repositoryModelReq) {
        Assert.notNull(repositoryModelReq, "????????????????????????");
        Assert.hasText(repositoryModelReq.getName(), "?????????????????????");
        Integer repoType = repositoryModelReq.getRepoType();
        Assert.state(repoType != null && (repoType == RepositoryModel.RepoType.Git.getCode() || repoType == RepositoryModel.RepoType.Svn.getCode()), "?????????????????????");
        Assert.hasText(repositoryModelReq.getGitUrl(), "?????????????????????");
        //
        Integer protocol = repositoryModelReq.getProtocol();
        Assert.state(protocol != null && (protocol == GitProtocolEnum.HTTP.getCode() || protocol == GitProtocolEnum.SSH.getCode()), "??????????????????????????????");
        // ????????????
        if (protocol == GitProtocolEnum.HTTP.getCode()) {
            //  http
            repositoryModelReq.setRsaPub(StrUtil.EMPTY);
            repositoryModelReq.setRsaPrv(StrUtil.EMPTY);
        } else if (protocol == GitProtocolEnum.SSH.getCode()) {
            // ssh
            repositoryModelReq.setPassword(StrUtil.emptyToDefault(repositoryModelReq.getPassword(), StrUtil.EMPTY));
        }
        String workspaceId = repositoryService.getCheckUserWorkspace(getRequest());
        //
        boolean repositoryUrl = this.checkRepositoryUrl(workspaceId, repositoryModelReq.getId(), repositoryModelReq.getGitUrl());
        Assert.state(!repositoryUrl, "????????????????????????????????????");
        // ????????????????????????ID
        repositoryModelReq.setWorkspaceId(workspaceId);
    }

    /**
     * ??????????????????????????????
     *
     * @param workspaceId ????????????ID
     * @param id          ??????ID
     * @param url         ?????? url
     * @return true ????????????????????????????????????
     */
    private boolean checkRepositoryUrl(String workspaceId, String id, String url) {
        // ????????????????????????
        Entity entity = Entity.create();
        if (StrUtil.isNotEmpty(id)) {
            Validator.validateGeneral(id, "?????????ID");
            entity.set("id", "<> " + id);
        }
        entity.set("workspaceId", workspaceId);
        entity.set("gitUrl", url);
        return repositoryService.exists(entity);
    }

    /**
     * ??????????????????????????????
     *
     * @param url ?????? url
     * @return true ????????????????????????????????????
     */
    private boolean checkRepositoryUrl(String url) {
        String workspaceId = repositoryService.getCheckUserWorkspace(getRequest());
        return this.checkRepositoryUrl(workspaceId, null, url);
    }

    /**
     * check and update ssh key
     *
     * @param repositoryModelReq ??????
     */
    private boolean checkAndUpdateSshKey(RepositoryModel repositoryModelReq) {
        if (repositoryModelReq.getProtocol() == GitProtocolEnum.SSH.getCode()) {
            // if rsa key is not empty
            if (StrUtil.isNotEmpty(repositoryModelReq.getRsaPrv())) {
                /**
                 * if rsa key is start with "file:"
                 * copy this file
                 */
                if (StrUtil.startWith(repositoryModelReq.getRsaPrv(), URLUtil.FILE_URL_PREFIX)) {
                    String rsaPath = StrUtil.removePrefix(repositoryModelReq.getRsaPrv(), URLUtil.FILE_URL_PREFIX);
                    if (!FileUtil.exist(rsaPath)) {
                        log.warn("there is no rsa file... {}", rsaPath);
                        return false;
                    }
                } else {
                    //File rsaFile = BuildUtil.getRepositoryRsaFile(repositoryModelReq.getId() + Const.ID_RSA);
                    //  or else put into file
                    //FileUtil.writeUtf8String(repositoryModelReq.getRsaPrv(), rsaFile);
                }
            }
        }
        return true;
    }

    /**
     * delete
     *
     * @param id ??????ID
     * @return json
     */
    @PostMapping(value = "/build/repository/delete")
    @Feature(method = MethodFeature.DEL)
    public Object delRepository(String id) {
        // ???????????????????????????
        Entity entity = Entity.create();
        entity.set("repositoryId", id);
        boolean exists = buildInfoService.exists(entity);
        Assert.state(!exists, "????????????????????????????????????????????????");

        repositoryService.delByKey(id, getRequest());
        File rsaFile = BuildUtil.getRepositoryRsaFile(id + ServerConst.ID_RSA);
        FileUtil.del(rsaFile);
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
    @GetMapping(value = "/build/repository/sort-item", produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.EDIT)
    public JsonMessage<String> sortItem(@ValidatorItem String id, @ValidatorItem String method, String compareId) {
        HttpServletRequest request = getRequest();
        if (StrUtil.equalsIgnoreCase(method, "top")) {
            repositoryService.sortToTop(id, request);
        } else if (StrUtil.equalsIgnoreCase(method, "up")) {
            repositoryService.sortMoveUp(id, compareId, request);
        } else if (StrUtil.equalsIgnoreCase(method, "down")) {
            repositoryService.sortMoveDown(id, compareId, request);
        } else {
            return new JsonMessage<>(400, "??????????????????" + method);
        }
        return new JsonMessage<>(200, "????????????");
    }

    /**
     * Gitee ??????
     */
    private static class GiteeUtil {

        /**
         * Gitee API ??????
         */
        private static final String GITEE_API_PREFIX = "https://gitee.com/api";

        /**
         * Gitee API ?????????
         */
        private static final String API_VERSION = "v5";

        /**
         * Gitee API ????????????
         */
        private static final String GITEE_API_URL_PREFIX = GITEE_API_PREFIX + "/" + API_VERSION;

        /**
         * ???????????????
         */
        private static final String ACCESS_TOKEN = "access_token";

        /**
         * ????????????: ????????????(created)???????????????(updated)?????????????????????(pushed)????????????????????????(full_name)?????????: full_name
         */
        private static final String SORT = "sort";

        /**
         * ???????????????
         */
        private static final String PAGE = "page";

        /**
         * ??????????????????????????? 100
         */
        private static final String PER_PAGE = "per_page";

        /**
         * ?????? Gitee ?????????
         *
         * @param token ???????????????
         * @return Gitee ?????????
         */
        private static String getGiteeUsername(String token) {
            // ?????????https://gitee.com/api/v5/swagger#/getV5User
            HttpResponse userResponse = HttpUtil.createGet(GITEE_API_URL_PREFIX + "/user", true)
                .form(ACCESS_TOKEN, token)
                .execute();
            Assert.state(userResponse.isOk(), "??????????????????" + userResponse.body());
            JSONObject userBody = JSONObject.parseObject(userResponse.body());
            return userBody.getString("login");
        }

        /**
         * ?????? Gitee ??????????????????
         *
         * @param token ???????????????
         * @param page  ????????????
         * @return
         */
        private static Map<String, Object> getGiteeRepos(String token, Page page, String condition) {
            // ?????????https://gitee.com/api/v5/swagger#/getV5UserRepos
            HttpResponse reposResponse = HttpUtil.createGet(GITEE_API_URL_PREFIX + "/user/repos", true)
                .form(ACCESS_TOKEN, token)
                .form(SORT, "pushed")
                .form(PAGE, page.getPageNumber())
                .form(PER_PAGE, page.getPageSize())
                // ???????????????
                .form("q", condition)
                .execute();
            String body = reposResponse.body();
            Assert.state(reposResponse.isOk(), "???????????????????????????" + body);

            // ????????????????????????????????????????????????
            String totalCountStr = reposResponse.header("total_count");
            int totalCount = Convert.toInt(totalCountStr, 0);
            //String totalPage = reposResponse.header("total_page");

            Map<String, Object> map = new HashMap<>(2);
            map.put("jsonArray", JSONArray.parseArray(body));
            // ????????????
            map.put("totalCount", totalCount);
            return map;
        }
    }

    /**
     * GitHub ??????
     */
    private static class GitHubUtil {

        /**
         * GitHub ?????????????????????
         * <p>
         * ?????????https://docs.github.com/en/rest/users/users#about-the-users-api
         */
        @Data
        private static class GitHubUserInfo {
            // ????????????????????????????????????

            /**
             * ??????????????????octocat
             */
            private String login;

            /**
             * ???????????????????????????2
             */
            private int public_repos;

            /**
             * ??????????????????????????????100
             */
            private int total_private_repos;

            /**
             * ??????????????????????????????100
             */
            private int owned_private_repos;
        }

        /**
         * GitHub ??????
         */
        private static final String GITHUB_HEADER_ACCEPT = "application/vnd.github.v3+json";

        /**
         * GitHub ?????? token ??????
         */
        private static final String GITHUB_TOKEN = "token ";

        /**
         * GitHub API ??????
         */
        private static final String GITHUB_API_PREFIX = "https://api.github.com";

        /**
         * ?????? GitHub ????????????
         *
         * @param token ?????? token
         * @return GitHub ????????????
         */
        private static GitHubUserInfo getGitHubUserInfo(String token) {
            // ?????????https://docs.github.com/en/rest/users/users#about-the-users-api
            HttpResponse response = HttpUtil
                .createGet(GITHUB_API_PREFIX + "/user")
                .header(Header.ACCEPT, GITHUB_HEADER_ACCEPT)
                .header(Header.AUTHORIZATION, GITHUB_TOKEN + token)
                .execute();
            String body = response.body();
            Assert.state(response.isOk(), "?????????????????????" + body);
            return JSONObject.parseObject(body, GitHubUserInfo.class);
        }

        /**
         * ?????? GitHub ????????????
         *
         * @param token
         */
        private static JSONArray getGitHubUserRepos(String token, Page page) {
            // ?????????https://docs.github.com/en/rest/repos/repos#list-repositories-for-the-authenticated-user
            HttpResponse response = HttpUtil
                .createGet(GITHUB_API_PREFIX + "/user/repos")
                .header(Header.ACCEPT, GITHUB_HEADER_ACCEPT)
                .header(Header.AUTHORIZATION, GITHUB_TOKEN + token)
                .form("access_token", token)
                .form("sort", "pushed")
                .form("page", page.getPageNumber())
                .form("per_page", page.getPageSize())
                .execute();
            String body = response.body();
            Assert.state(response.isOk(), "???????????????????????????" + body);
            return JSONArray.parseArray(body);
        }
    }

    /**
     * GitLab ??????
     */
    private static class GitLabUtil {

        /**
         * GitLab ???????????????????????????https://docs.gitlab.com/ee/api/version.html
         */
        @Data
        private static class GitLabVersionInfo {

            /**
             * ??????????????????8.13.0-pre
             */
            private String version;

            /**
             * ??????????????????4e963fe
             */
            private String revision;

            /**
             * API ??????????????????v4
             */
            private String apiVersion;
        }

        /**
         * GitLab ?????????????????????key???GitLab ?????????value???GitLabVersionInfo
         */
        private static final Map<String, GitLabVersionInfo> gitlabVersionMap = new ConcurrentHashMap<>();

        /**
         * ?????? GitLab ??????
         *
         * @param gitlabAddress GitLab ??????
         * @param token         ?????? token
         * @return ????????????
         */
        private static HttpResponse getGitLabVersion(String gitlabAddress, String token, String apiVersion) {
            // ?????????https://docs.gitlab.com/ee/api/version.html
            return HttpUtil.createGet(StrUtil.format("{}/api/{}/version", gitlabAddress, apiVersion), true)
                .header("PRIVATE-TOKEN", token)
                .execute();
        }

        /**
         * ?????? GitLab ????????????
         *
         * @param url   GitLab ??????
         * @param token ?????? token
         */
        private static GitLabVersionInfo getGitLabVersionInfo(String url, String token) {
            // ????????????????????????????????????
            GitLabVersionInfo gitLabVersionInfo = gitlabVersionMap.get(url);
            if (gitLabVersionInfo != null) {
                return gitLabVersionInfo;
            }

            // ?????? GitLab ???????????????
            GitLabVersionInfo glvi = null;
            String apiVersion = "v4";
            HttpResponse v4 = getGitLabVersion(url, token, apiVersion);
            if (v4 != null) {
                glvi = JSON.parseObject(v4.body(), GitLabVersionInfo.class);
            } else {
                apiVersion = "v3";
                HttpResponse v3 = getGitLabVersion(url, token, apiVersion);
                if (v3 != null) {
                    glvi = JSON.parseObject(v3.body(), GitLabVersionInfo.class);
                }
            }

            Assert.state(glvi != null, "?????? GitLab ??????????????????????????? GitLab ????????? token ????????????");

            // ??????????????????
            glvi.setApiVersion(apiVersion);
            gitlabVersionMap.put(url, glvi);

            return glvi;
        }

        /**
         * ?????? GitLab API ?????????
         *
         * @param url   GitLab ??????
         * @param token ?????? token
         * @return GitLab API ??????????????????v4
         */
        private static String getGitLabApiVersion(String url, String token) {
            return getGitLabVersionInfo(url, token).getApiVersion();
        }

        /**
         * ?????? GitLab ????????????
         *
         * @param gitlabAddress GitLab ??????
         * @param token         ?????? token
         * @return ????????????
         */
        private static HttpResponse getGitLabUserInfo(String gitlabAddress, String token) {
            // ?????????https://docs.gitlab.com/ee/api/users.html
            return HttpUtil.createGet(
                    StrUtil.format(
                        "{}/api/{}/user",
                        gitlabAddress,
                        getGitLabApiVersion(gitlabAddress, token)
                    ), true
                )
                .form("access_token", token)
                .timeout(5000)
                .execute();
        }

        /**
         * ?????? GitLab ????????????
         *
         * @param gitlabAddress GitLab ??????
         * @param token         ?????? token
         * @return ????????????
         */
        private static Map<String, Object> getGitLabRepos(String gitlabAddress, String token, Page page, String condition) {
            // ?????????https://docs.gitlab.com/ee/api/projects.html
            HttpResponse reposResponse = HttpUtil.createGet(
                    StrUtil.format(
                        "{}/api/{}/projects",
                        gitlabAddress,
                        getGitLabApiVersion(gitlabAddress, token)
                    ), true
                )
                .form("private_token", token)
                .form("membership", true)
                // ??? simple=true ???????????????????????? visibility???????????????????????????????????????????????????????????????
//                .form("simple", true)
                .form("order_by", "updated_at")
                .form("page", page.getPageNumber())
                .form("per_page", page.getPageSize())
                .form("search", condition)
                .execute();

            String body = reposResponse.body();
            Assert.state(reposResponse.isOk(), "???????????????????????????" + body);

            String totalCountStr = reposResponse.header("X-Total");
            int totalCount = Convert.toInt(totalCountStr, 0);
            //String totalPage = reposResponse.header("total_page");

            Map<String, Object> map = new HashMap<>(2);
            map.put("body", body);
            map.put("total", totalCount);

            return map;
        }
    }

}
