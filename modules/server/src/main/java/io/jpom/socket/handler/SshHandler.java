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
package io.jpom.socket.handler;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.ssh.ChannelType;
import cn.hutool.extra.ssh.JschUtil;
import cn.jiangzeyin.common.spring.SpringUtil;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONValidator;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import io.jpom.model.data.SshModel;
import io.jpom.model.user.UserModel;
import io.jpom.permission.ClassFeature;
import io.jpom.permission.Feature;
import io.jpom.permission.MethodFeature;
import io.jpom.service.dblog.SshTerminalExecuteLogService;
import io.jpom.service.node.ssh.SshService;
import io.jpom.service.user.UserBindWorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ssh ??????2
 *
 * @author bwcx_jzy
 * @since 2019/8/9
 */
@Feature(cls = ClassFeature.SSH_TERMINAL, method = MethodFeature.EXECUTE)
@Slf4j
public class SshHandler extends BaseTerminalHandler {

    private static final ConcurrentHashMap<String, HandlerItem> HANDLER_ITEM_CONCURRENT_HASH_MAP = new ConcurrentHashMap<>();
    private static SshTerminalExecuteLogService sshTerminalExecuteLogService;
    private static UserBindWorkspaceService userBindWorkspaceService;

    private static void init() {
        if (sshTerminalExecuteLogService == null) {
            sshTerminalExecuteLogService = SpringUtil.getBean(SshTerminalExecuteLogService.class);
        }
        if (userBindWorkspaceService == null) {
            userBindWorkspaceService = SpringUtil.getBean(UserBindWorkspaceService.class);
        }
    }

    @Override
    public void afterConnectionEstablishedImpl(WebSocketSession session) throws Exception {
        Map<String, Object> attributes = session.getAttributes();
        SshModel sshItem = (SshModel) attributes.get("dataItem");

        super.logOpt(this.getClass(), attributes, attributes);
        //
        HandlerItem handlerItem;
        try {
            handlerItem = new HandlerItem(session, sshItem);
            handlerItem.startRead();
        } catch (Exception e) {
            // ?????????????????? @author jzy
            log.error("ssh ?????????????????????", e);
            sendBinary(session, "ssh ?????????????????????");
            this.destroy(session);
            return;
        }
        HANDLER_ITEM_CONCURRENT_HASH_MAP.put(session.getId(), handlerItem);
        //
        Thread.sleep(1000);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        HandlerItem handlerItem = HANDLER_ITEM_CONCURRENT_HASH_MAP.get(session.getId());
        if (handlerItem == null) {
            sendBinary(session, "???????????????");
            IoUtil.close(session);
            return;
        }
        String payload = message.getPayload();
        try (JSONValidator from = JSONValidator.from(payload)) {
            if (from.getType() == JSONValidator.Type.Object) {
                JSONObject jsonObject = JSONObject.parseObject(payload);
                String data = jsonObject.getString("data");
                if (StrUtil.equals(data, "jpom-heart")) {
                    // ?????????????????????
                    return;
                }
                if (StrUtil.equals(data, "resize")) {
                    // ???????????????
                    handlerItem.resize(jsonObject);
                    return;
                }
            }
        }
        init();
        Map<String, Object> attributes = session.getAttributes();
        UserModel userInfo = (UserModel) attributes.get("userInfo");
        // ???????????????????????????
        String workspaceId = handlerItem.sshItem.getWorkspaceId();
        boolean sshCommandNotLimited = userBindWorkspaceService.exists(userInfo, workspaceId + UserBindWorkspaceService.SSH_COMMAND_NOT_LIMITED);
        try {
            this.sendCommand(handlerItem, payload, userInfo, sshCommandNotLimited);
        } catch (Exception e) {
            sendBinary(session, "Failure:" + e.getMessage());
            log.error("??????????????????", e);
        }
    }

    private void sendCommand(HandlerItem handlerItem, String data, UserModel userInfo, boolean sshCommandNotLimited) throws Exception {
        if (handlerItem.checkInput(data, userInfo, sshCommandNotLimited)) {
            handlerItem.outputStream.write(data.getBytes());
        } else {
            handlerItem.outputStream.write("??????????????????????????????".getBytes());
            handlerItem.outputStream.flush();
            handlerItem.outputStream.write(new byte[]{3});
        }
        handlerItem.outputStream.flush();
    }

    /**
     * ????????????????????????
     *
     * @param session ??????
     * @param command ?????????
     * @param refuse  ????????????
     */
    private void logCommands(WebSocketSession session, String command, boolean refuse) {
        List<String> split = StrUtil.splitTrim(command, StrUtil.CR);
        // ???????????????????????????, ?????????????????????????????????????????????????????????????????????
        boolean all = StrUtil.endWith(command, StrUtil.CR);
        int size = split.size();
        split = CollUtil.sub(split, 0, all ? size : size - 1);
        if (CollUtil.isEmpty(split)) {
            return;
        }
        // ??????????????????
        Map<String, Object> attributes = session.getAttributes();
        UserModel userInfo = (UserModel) attributes.get("userInfo");
        String ip = (String) attributes.get("ip");
        String userAgent = (String) attributes.get(HttpHeaders.USER_AGENT);
        SshModel sshItem = (SshModel) attributes.get("dataItem");
        //
        sshTerminalExecuteLogService.batch(userInfo, sshItem, ip, userAgent, refuse, split);
    }

    private class HandlerItem implements Runnable {
        private final WebSocketSession session;
        private final InputStream inputStream;
        private final OutputStream outputStream;
        private final Session openSession;
        private final ChannelShell channel;
        private final SshModel sshItem;
        private final StringBuilder nowLineInput = new StringBuilder();

        HandlerItem(WebSocketSession session, SshModel sshItem) throws IOException {
            this.session = session;
            this.sshItem = sshItem;
            this.openSession = SshService.getSessionByModel(sshItem);
            this.channel = (ChannelShell) JschUtil.createChannel(openSession, ChannelType.SHELL);
            this.inputStream = channel.getInputStream();
            this.outputStream = channel.getOutputStream();
        }

        void startRead() throws JSchException {
            this.channel.connect(sshItem.timeout());
            ThreadUtil.execute(this);
        }

        /**
         * ?????? ???????????????
         *
         * @param jsonObject ??????
         */
        private void resize(JSONObject jsonObject) {
            Integer rows = Convert.toInt(jsonObject.getString("rows"), 10);
            Integer cols = Convert.toInt(jsonObject.getString("cols"), 10);
            Integer wp = Convert.toInt(jsonObject.getString("wp"), 10);
            Integer hp = Convert.toInt(jsonObject.getString("hp"), 10);
            this.channel.setPtySize(cols, rows, wp, hp);
        }

        /**
         * ?????????????????????
         *
         * @param msg ??????
         * @return ??????????????????????????????
         */
        private String append(String msg) {
            char[] x = msg.toCharArray();
            if (x.length == 1 && x[0] == 127) {
                // ?????????
                int length = nowLineInput.length();
                if (length > 0) {
                    nowLineInput.delete(length - 1, length);
                }
            } else {
                nowLineInput.append(msg);
            }
            return nowLineInput.toString();
        }

        /**
         * ?????????????????????????????????????????????????????????
         *
         * @param msg                  ??????
         * @param userInfo             ??????
         * @param sshCommandNotLimited ??????????????????
         * @return true ??????????????????
         */
        public boolean checkInput(String msg, UserModel userInfo, boolean sshCommandNotLimited) {
            String allCommand = this.append(msg);
            boolean refuse;
            // ????????????????????????,?????????????????????
            boolean systemUser = userInfo.isSuperSystemUser() || sshCommandNotLimited;
            if (StrUtil.equalsAny(msg, StrUtil.CR, StrUtil.TAB)) {
                String join = nowLineInput.toString();
                if (StrUtil.equals(msg, StrUtil.CR)) {
                    nowLineInput.setLength(0);
                }
                refuse = SshModel.checkInputItem(sshItem, join);
            } else {
                // ????????????
                refuse = SshModel.checkInputItem(sshItem, msg);
            }
            // ?????????????????????
            logCommands(session, allCommand, refuse);
            return systemUser || refuse;
        }


        @Override
        public void run() {
            try {
                byte[] buffer = new byte[1024];
                int i;
                //???????????????????????????????????????????????????????????????????????????
                while ((i = inputStream.read(buffer)) != -1) {
                    sendBinary(session, new String(Arrays.copyOfRange(buffer, 0, i), sshItem.charset()));
                }
            } catch (Exception e) {
                if (!this.openSession.isConnected()) {
                    return;
                }
                log.error("????????????", e);
                SshHandler.this.destroy(this.session);
            }
        }
    }

    @Override
    public void destroy(WebSocketSession session) {
        HandlerItem handlerItem = HANDLER_ITEM_CONCURRENT_HASH_MAP.get(session.getId());
        if (handlerItem != null) {
            IoUtil.close(handlerItem.inputStream);
            IoUtil.close(handlerItem.outputStream);
            JschUtil.close(handlerItem.channel);
            JschUtil.close(handlerItem.openSession);
        }
        IoUtil.close(session);
        HANDLER_ITEM_CONCURRENT_HASH_MAP.remove(session.getId());
    }
}
