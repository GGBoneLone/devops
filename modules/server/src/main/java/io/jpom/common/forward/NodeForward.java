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
package io.jpom.common.forward;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.IORuntimeException;
import cn.hutool.core.net.url.UrlQuery;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.extra.servlet.ServletUtil;
import cn.hutool.http.*;
import cn.jiangzeyin.common.JsonMessage;
import cn.jiangzeyin.common.spring.SpringUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import io.jpom.common.BaseServerController;
import io.jpom.common.Const;
import io.jpom.model.data.NodeModel;
import io.jpom.model.user.UserModel;
import io.jpom.service.node.NodeService;
import io.jpom.system.AgentException;
import io.jpom.system.AuthorizeException;
import io.jpom.system.ConfigBean;
import io.jpom.system.ServerExtConfigBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.util.Map;
import java.util.Set;

/**
 * ??????????????????
 *
 * @author jiangzeyin
 * @since 2019/4/16
 */
@Slf4j
public class NodeForward {

    /**
     * ??????????????????
     *
     * @param nodeModel ??????
     * @param request   ??????
     * @param nodeUrl   ?????????url
     * @param <T>       ??????
     * @return JSON
     */
    public static <T> JsonMessage<T> request(NodeModel nodeModel, HttpServletRequest request, NodeUrl nodeUrl) {
        return request(nodeModel, request, nodeUrl, true, null, null, null, null);
    }

    /**
     * ??????????????????
     *
     * @param nodeModel  ??????
     * @param nodeUrl    ?????????url
     * @param jsonObject ??????
     * @param userModel  user
     * @return JSON
     */
    public static JsonMessage<String> request(NodeModel nodeModel, NodeUrl nodeUrl, UserModel userModel, JSONObject jsonObject) {
        return request(nodeModel, null, nodeUrl, true, userModel, jsonObject, null, null);
    }

    /**
     * ??????????????????
     *
     * @param nodeModel ??????
     * @param nodeUrl   ?????????url
     * @param pName     ????????????
     * @param pVal      ????????????
     * @param val       ????????????
     * @return JSON
     */
    public static <T> JsonMessage<T> requestBySys(NodeModel nodeModel, NodeUrl nodeUrl, String pName, Object pVal, Object... val) {
        return request(nodeModel, null, nodeUrl, false, null, null, pName, pVal, val);
    }

    /**
     * post body ????????????
     *
     * @param nodeModel ??????
     * @param nodeUrl   ?????????url
     * @param userModel ??????
     * @param jsonData  ??????
     * @param <T>       ??????
     * @return JSON
     */
    public static <T> JsonMessage<T> requestBody(NodeModel nodeModel,
                                                 NodeUrl nodeUrl,
                                                 UserModel userModel,
                                                 JSONObject jsonData) {
        String url = nodeModel.getRealUrl(nodeUrl);
        HttpRequest httpRequest = HttpUtil.createPost(url);

        addUser(httpRequest, nodeModel, nodeUrl, userModel);

        httpRequest.body(jsonData.toString(), ContentType.JSON.getValue());

        try (HttpResponse response = httpRequest.execute()) {
            //
            return parseBody(httpRequest, response, nodeModel);
        } catch (Exception e) {
            throw NodeForward.responseException(e, nodeModel);
        }

    }

    /**
     * ??????????????????
     *
     * @param nodeModel ??????
     * @param request   ??????
     * @param nodeUrl   ?????????url
     * @param pVal      ????????????
     * @param pName     ????????????
     * @param userModel ??????
     * @param jsonData  ??????
     * @param mustUser  ??????????????????user
     * @param val       ????????????
     * @param <T>       ??????
     * @return JSON
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> JsonMessage<T> request(NodeModel nodeModel,
                                              HttpServletRequest request,
                                              NodeUrl nodeUrl,
                                              boolean mustUser,
                                              UserModel userModel,
                                              JSONObject jsonData,
                                              String pName,
                                              Object pVal,
                                              Object... val) {
        String url = nodeModel.getRealUrl(nodeUrl);
        HttpRequest httpRequest = HttpUtil.createPost(url);
        //
        if (mustUser) {
            if (userModel == null) {
                userModel = BaseServerController.getUserModel();
            }
        }
        //
        addUser(httpRequest, nodeModel, nodeUrl, userModel);
        Map params = null;
        if (request != null) {
            params = request.getParameterMap();
            for (Map.Entry<String, String[]> entry : (Iterable<Map.Entry<String, String[]>>) params.entrySet()) {
                String[] values = entry.getValue();
                if (values != null) {
                    for (int i = 0, len = values.length; i < len; i++) {
                        // ?????? URL ??????????????? ???????????? ?????????
                        values[i] = URLUtil.encodeAll(values[i]);
                    }
                    entry.setValue(values);
                }
            }
        }
        httpRequest.form(pName, pVal, val);
        //
        if (jsonData != null) {
            JSONObject clone = jsonData.clone();
            // ?????? URL ??????????????? ???????????? ?????????
            Set<Map.Entry<String, Object>> entries = clone.entrySet();
            for (Map.Entry<String, Object> entry : entries) {
                Object value = entry.getValue();
                if (value instanceof String) {
                    entry.setValue(URLUtil.encodeAll((String) value));
                }
            }
            httpRequest.form(clone);
        }
        httpRequest.form(params);
        try (HttpResponse response = httpRequest.execute()) {
            //
            return parseBody(httpRequest, response, nodeModel);
        } catch (Exception e) {
            throw NodeForward.responseException(e, nodeModel);
        }
    }

    /**
     * ????????? ??????????????????
     *
     * @param exception ??????
     * @param nodeModel ?????????
     */
    private static AgentException responseException(Exception exception, NodeModel nodeModel) {
        String message = exception.getMessage();
        Throwable cause = exception.getCause();
        log.error("node [{}] connect failed...message: [{}]", nodeModel.getName(), message);
        if (exception instanceof IORuntimeException) {
            if (cause instanceof java.net.ConnectException || cause instanceof java.net.SocketTimeoutException) {
                return new AgentException(nodeModel.getName() + "?????????????????????????????????,????????????????????????????????????????????? IP ?????????" +
                    "???????????????????????????,???????????????," +
                    "??????????????????????????????????????????????????????????????????" + message);
            }
        } else if (exception instanceof cn.hutool.http.HttpException) {
            if (cause instanceof java.net.SocketTimeoutException) {
                return new AgentException(nodeModel.getName() + "????????????????????????,?????????????????????????????????????????????????????????????????????????????????,?????????????????????????????????????????????" + message);
            }
            if (cause instanceof IOException && StrUtil.containsIgnoreCase(message, "Error writing to server")) {
                return new AgentException(nodeModel.getName() + "??????????????????,??????????????????????????????????????????????????????" + message);
            }
        }
        return new AgentException(nodeModel.getName() + "???????????????" + message);
    }

    /**
     * ??????????????????,???????????????
     *
     * @param nodeModel ??????
     * @param request   ??????
     * @param nodeUrl   ?????????url
     * @param tClass    ???????????????
     * @param <T>       ??????
     * @return T
     */
    public static <T> T requestData(NodeModel nodeModel, NodeUrl nodeUrl, HttpServletRequest request, Class<T> tClass) {
        JsonMessage<T> jsonMessage = request(nodeModel, request, nodeUrl);
        return jsonMessage.getData(tClass);
    }

    /**
     * ??????????????????,???????????????
     *
     * @param nodeModel  ??????
     * @param nodeUrl    ?????????url
     * @param tClass     ???????????????
     * @param <T>        ??????
     * @param name       ?????????
     * @param parameters ????????????
     * @param value      ???
     * @return T
     */
    public static <T> T requestData(NodeModel nodeModel, NodeUrl nodeUrl, Class<T> tClass, String name, Object value, Object... parameters) {
        String url = nodeModel.getRealUrl(nodeUrl);
        //
        HttpRequest httpRequest = HttpUtil.createPost(url);
        if (name != null && value != null) {
            httpRequest.form(name, value, parameters);
        }
        //
        addUser(httpRequest, nodeModel, nodeUrl);
        try (HttpResponse response = httpRequest.execute();) {
            //
            JsonMessage<T> jsonMessage = parseBody(httpRequest, response, nodeModel);
            return jsonMessage.getData(tClass);
        } catch (Exception e) {
            throw NodeForward.responseException(e, nodeModel);
        }
    }


    /**
     * ????????????????????????
     *
     * @param nodeModel ??????
     * @param request   ??????
     * @param nodeUrl   ?????????url
     * @return json
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static JsonMessage<String> requestMultipart(NodeModel nodeModel, MultipartHttpServletRequest request, NodeUrl nodeUrl) {
        String url = nodeModel.getRealUrl(nodeUrl);
        HttpRequest httpRequest = HttpUtil.createPost(url);
        addUser(httpRequest, nodeModel, nodeUrl);
        //
        Map params = ServletUtil.getParams(request);
        httpRequest.form(params);
        //
        Map<String, MultipartFile> fileMap = request.getFileMap();
        fileMap.forEach((s, multipartFile) -> {
            try {
                httpRequest.form(s, multipartFile.getBytes(), multipartFile.getOriginalFilename());
            } catch (IOException e) {
                log.error("??????????????????", e);
            }
        });
        // @author jzy add  timeout
        httpRequest.timeout(ServerExtConfigBean.getInstance().getUploadFileTimeOut());
        try (HttpResponse response = httpRequest.execute()) {
            return parseBody(httpRequest, response, nodeModel);
        } catch (Exception e) {
            throw NodeForward.responseException(e, nodeModel);
        }

    }

    /**
     * ????????????????????????
     *
     * @param nodeModel ??????
     * @param fileName  ???????????????
     * @param file      ???????????????
     * @param nodeUrl   ?????????url
     * @return json
     */
    public static JsonMessage<String> requestMultipart(NodeModel nodeModel, String fileName, File file, NodeUrl nodeUrl) {
        String url = nodeModel.getRealUrl(nodeUrl);
        HttpRequest httpRequest = HttpUtil.createPost(url);
        addUser(httpRequest, nodeModel, nodeUrl);
        //
        httpRequest.form(fileName, file);
        // @author jzy add  timeout
        httpRequest.timeout(ServerExtConfigBean.getInstance().getUploadFileTimeOut());
        try (HttpResponse response = httpRequest.execute()) {
            return parseBody(httpRequest, response, nodeModel);
        } catch (Exception e) {
            throw NodeForward.responseException(e, nodeModel);
        }

    }

    /**
     * ????????????????????????
     *
     * @param nodeModel ??????
     * @param request   ??????
     * @param response  ??????
     * @param nodeUrl   ?????????url
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void requestDownload(NodeModel nodeModel, HttpServletRequest request, HttpServletResponse response, NodeUrl nodeUrl) {
        String url = nodeModel.getRealUrl(nodeUrl);
        HttpRequest httpRequest = HttpUtil.createGet(url, true);
        addUser(httpRequest, nodeModel, nodeUrl);
        //
        Map params = ServletUtil.getParams(request);
        httpRequest.form(params);
        // @author jzy add  timeout
        httpRequest.timeout(ServerExtConfigBean.getInstance().getUploadFileTimeOut());
        //
        try (HttpResponse response1 = httpRequest.execute()) {
            String contentDisposition = response1.header("Content-Disposition");
            response.setHeader("Content-Disposition", contentDisposition);
            String contentType = response1.header("Content-Type");
            response.setContentType(contentType);
            ServletUtil.write(response, response1.bodyStream());
        } catch (Exception e) {
            throw NodeForward.responseException(e, nodeModel);
        }
    }

    private static void addUser(HttpRequest httpRequest, NodeModel nodeModel, NodeUrl nodeUrl) {
        UserModel userModel = BaseServerController.getUserModel();
        addUser(httpRequest, nodeModel, nodeUrl, userModel);
    }

    /**
     * ??????agent ????????????header
     *
     * @param httpRequest request
     * @param nodeModel   ??????
     * @param userModel   ??????
     */
    private static void addUser(HttpRequest httpRequest, NodeModel nodeModel, NodeUrl nodeUrl, UserModel userModel) {
        // ??????????????????
        if (!nodeModel.isOpenStatus()) {
            throw new AgentException(nodeModel.getName() + "???????????????");
        }
        if (userModel != null) {
            httpRequest.header(ConfigBean.JPOM_SERVER_USER_NAME, URLUtil.encode(userModel.getId()));
//            httpRequest.header(ConfigBean.JPOM_SERVER_SYSTEM_USER_ROLE, userModel.getUserRole(nodeModel).name());
        }
        if (StrUtil.isEmpty(nodeModel.getLoginPwd())) {
            NodeService nodeService = SpringUtil.getBean(NodeService.class);
            NodeModel model = nodeService.getByKey(nodeModel.getId(), false);
            nodeModel.setLoginPwd(model.getLoginPwd());
            nodeModel.setLoginName(model.getLoginName());
        }
        httpRequest.header(ConfigBean.JPOM_AGENT_AUTHORIZE, nodeModel.toAuthorize());
        httpRequest.header(Const.WORKSPACEID_REQ_HEADER, nodeModel.getWorkspaceId());
        //
        int timeOut = nodeModel.getTimeOut();
        if (nodeUrl.getTimeOut() != -1 && timeOut > 0) {
            //
            timeOut = Math.max(timeOut, 2);
            httpRequest.timeout(timeOut * 1000);
        }
        // ?????? http proxy
        Proxy proxy = nodeModel.proxy();
        httpRequest.setProxy(proxy);
    }

    /**
     * ????????????socket ??????
     *
     * @param nodeModel ????????????
     * @param nodeUrl   url
     * @return url
     */
    public static String getSocketUrl(NodeModel nodeModel, NodeUrl nodeUrl, UserModel userInfo, Object... parameters) {
        String ws;
        if ("https".equalsIgnoreCase(nodeModel.getProtocol())) {
            ws = "wss";
        } else {
            ws = "ws";
        }
        if (StrUtil.isEmpty(nodeModel.getLoginPwd())) {
            NodeService nodeService = SpringUtil.getBean(NodeService.class);
            NodeModel model = nodeService.getByKey(nodeModel.getId(), false);
            nodeModel.setLoginPwd(model.getLoginPwd());
            nodeModel.setLoginName(model.getLoginName());
        }
        UrlQuery urlQuery = new UrlQuery();
        urlQuery.add(ConfigBean.JPOM_AGENT_AUTHORIZE, nodeModel.toAuthorize());
        //
        String optUser = userInfo.getId();
        optUser = URLUtil.encode(optUser);
        urlQuery.add("optUser", optUser);
        if (ArrayUtil.isNotEmpty(parameters)) {
            for (int i = 0; i < parameters.length; i += 2) {
                Object parameter = parameters[i + 1];
                String value = Convert.toStr(parameter, StrUtil.EMPTY);
                urlQuery.add(parameters[i].toString(), URLUtil.encode(value));
            }
        }
        // ???????????????-???????????? @author jzy
        urlQuery.add("name", URLUtil.encode(nodeModel.getLoginName()));
        urlQuery.add("password", URLUtil.encode(nodeModel.getLoginPwd()));
        String format = StrUtil.format("{}://{}{}?{}", ws, nodeModel.getUrl(), nodeUrl.getUrl(), urlQuery.toString());
        log.debug("web socket url:{}", format);
        return format;
    }

    /**
     * ????????????
     *
     * @param response ??????
     * @return json
     */
    private static <T> JsonMessage<T> parseBody(HttpRequest httpRequest, HttpResponse response, NodeModel nodeModel) {
        int status = response.getStatus();
        String body = response.body();
        if (log.isDebugEnabled()) {
            log.debug("{} -> {} {} {} {}", nodeModel.getName(), httpRequest.getUrl(), httpRequest.getMethod(), httpRequest.form(), body);
        }
        if (status != HttpStatus.HTTP_OK) {
            log.warn("{} ???????????? ??????????????????{} {}", nodeModel.getName(), status, body);
            throw new AgentException(nodeModel.getName() + " ??????????????????,??????????????????" + status);
        }
        return toJsonMessage(body);
    }

    private static <T> JsonMessage<T> toJsonMessage(String body) {
        if (StrUtil.isEmpty(body)) {
            throw new AgentException("agent ?????????????????????");
        }
        JsonMessage<T> jsonMessage = JSON.parseObject(body, new TypeReference<JsonMessage<T>>() {
        });
        if (jsonMessage.getCode() == ConfigBean.AUTHORIZE_ERROR) {
            throw new AuthorizeException(jsonMessage, jsonMessage.getMsg());
        }
        return jsonMessage;
    }
}
