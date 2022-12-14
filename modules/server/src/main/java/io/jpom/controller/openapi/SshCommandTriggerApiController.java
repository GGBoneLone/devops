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
package io.jpom.controller.openapi;

import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.servlet.ServletUtil;
import cn.jiangzeyin.common.JsonMessage;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.jpom.common.BaseJpomController;
import io.jpom.common.BaseServerController;
import io.jpom.common.ServerOpenApi;
import io.jpom.common.interceptor.NotLogin;
import io.jpom.model.data.CommandModel;
import io.jpom.model.user.UserModel;
import io.jpom.service.node.command.CommandService;
import io.jpom.service.user.TriggerTokenLogServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @author bwcx_jzy
 * @since 2022/7/25
 */
@RestController
@NotLogin
@Slf4j
public class SshCommandTriggerApiController extends BaseJpomController {

    private final CommandService commandService;
    private final TriggerTokenLogServer triggerTokenLogServer;

    public SshCommandTriggerApiController(CommandService commandService,
                                          TriggerTokenLogServer triggerTokenLogServer) {
        this.commandService = commandService;
        this.triggerTokenLogServer = triggerTokenLogServer;
    }

    /**
     * ????????????
     *
     * @param id    ??????ID
     * @param token ?????????token
     * @return json
     */
    @RequestMapping(value = ServerOpenApi.SSH_COMMAND_TRIGGER_URL, produces = MediaType.APPLICATION_JSON_VALUE)
    public String trigger2(@PathVariable String id, @PathVariable String token) {
        CommandModel item = commandService.getByKey(id);
        Assert.notNull(item, "??????????????????");
        Assert.state(StrUtil.equals(token, item.getTriggerToken()), "??????token??????,??????????????????");
        //
        Assert.hasText(item.getSshIds(), "????????????????????? SSH ????????????????????????????????????");
        UserModel userModel = triggerTokenLogServer.getUserByToken(token, commandService.typeName());
        //
        Assert.notNull(userModel, "??????token??????,??????????????????:-1");

        String batchId = null;
        try {
            BaseServerController.resetInfo(userModel);
            batchId = commandService.executeBatch(item, item.getDefParams(), item.getSshIds(), 2);
        } catch (Exception e) {
            log.error("??????????????????SSH??????????????????", e);
            return JsonMessage.getString(500, "???????????????" + e.getMessage());
        }
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("batchId", batchId);
        return JsonMessage.getString(200, "????????????", jsonObject);
    }


    /**
     * ???????????????
     * <p>
     * ?????? <code>[
     * {
     * "id":"1",
     * "token":"a"
     * }
     * ]</code>
     * <p>
     * ?????? <code>[
     * {
     * "id":"1",
     * "token":"a",
     * "batchId":"1",
     * "msg":"??????????????????",
     * }
     * ]</code>
     *
     * @return json
     */
    @PostMapping(value = ServerOpenApi.SSH_COMMAND_TRIGGER_BATCH, produces = MediaType.APPLICATION_JSON_VALUE)
    public String triggerBatch() {
        try {
            String body = ServletUtil.getBody(getRequest());
            JSONArray jsonArray = JSONArray.parseArray(body);
            List<Object> collect = jsonArray.stream().peek(o -> {
                JSONObject jsonObject = (JSONObject) o;
                String id = jsonObject.getString("id");
                String token = jsonObject.getString("token");
                CommandModel item = commandService.getByKey(id);
                if (item == null) {
                    jsonObject.put("msg", "??????????????????");
                    return;
                }
                UserModel userModel = triggerTokenLogServer.getUserByToken(token, commandService.typeName());
                if (userModel == null) {
                    jsonObject.put("msg", "????????????????????????,??????????????????");
                    return;
                }
                //
                if (!StrUtil.equals(token, item.getTriggerToken())) {
                    jsonObject.put("msg", "??????token??????,??????????????????");
                    return;
                }
                BaseServerController.resetInfo(userModel);
                String batchId = null;
                try {
                    batchId = commandService.executeBatch(item, item.getDefParams(), item.getSshIds(), 2);
                } catch (Exception e) {
                    log.error("????????????????????????????????????", e);
                    jsonObject.put("msg", "???????????????" + e.getMessage());
                }
                jsonObject.put("batchId", batchId);
                //
            }).collect(Collectors.toList());
            return JsonMessage.getString(200, "????????????", collect);
        } catch (Exception e) {
            log.error("SSH ????????????????????????", e);
            return JsonMessage.getString(500, "????????????", e.getMessage());
        }
    }
}
