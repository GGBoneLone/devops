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
package io.jpom.service.node.command;

import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.io.LineHandler;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.cron.task.Task;
import cn.hutool.db.Entity;
import cn.hutool.extra.ssh.ChannelType;
import cn.hutool.extra.ssh.JschUtil;
import cn.hutool.system.SystemUtil;
import com.alibaba.fastjson.JSONObject;
import com.jcraft.jsch.ChannelExec;
import io.jpom.common.BaseServerController;
import io.jpom.cron.CronUtils;
import io.jpom.cron.ICron;
import io.jpom.model.data.CommandExecLogModel;
import io.jpom.model.data.CommandModel;
import io.jpom.model.data.SshModel;
import io.jpom.model.user.UserModel;
import io.jpom.service.ITriggerToken;
import io.jpom.service.h2db.BaseWorkspaceService;
import io.jpom.service.node.ssh.SshService;
import io.jpom.service.system.WorkspaceEnvVarService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ????????????
 *
 * @author : Arno
 * @since : 2021/12/6 22:11
 */
@Service
@Slf4j
public class CommandService extends BaseWorkspaceService<CommandModel> implements ICron<CommandModel>, ITriggerToken {

    private final SshService sshService;
    private final CommandExecLogService commandExecLogService;
    private final WorkspaceEnvVarService workspaceEnvVarService;

    private static final byte[] LINE_BYTES = SystemUtil.getOsInfo().getLineSeparator().getBytes(CharsetUtil.CHARSET_UTF_8);

    public CommandService(SshService sshService,
                          CommandExecLogService commandExecLogService,
                          WorkspaceEnvVarService workspaceEnvVarService) {
        this.sshService = sshService;
        this.commandExecLogService = commandExecLogService;
        this.workspaceEnvVarService = workspaceEnvVarService;
    }

    @Override
    public void insert(CommandModel commandModel) {
        super.insert(commandModel);
        this.checkCron(commandModel);
    }

    @Override
    public int updateById(CommandModel info, HttpServletRequest request) {
        int update = super.updateById(info, request);
        if (update > 0) {
            this.checkCron(info);
        }
        return update;
    }

    @Override
    public int delByKey(String keyValue, HttpServletRequest request) {
        int delByKey = super.delByKey(keyValue, request);
        if (delByKey > 0) {
            String taskId = "ssh_command:" + keyValue;
            CronUtils.remove(taskId);
        }
        return delByKey;
    }

    /**
     * ?????????????????? ??????
     *
     * @param buildInfoModel ????????????
     */
    @Override
    public boolean checkCron(CommandModel buildInfoModel) {
        String id = buildInfoModel.getId();
        String taskId = "ssh_command:" + id;
        String autoExecCron = buildInfoModel.getAutoExecCron();
        if (StrUtil.isEmpty(autoExecCron)) {
            CronUtils.remove(taskId);
            return false;
        }
        log.debug("start ssh command cron {} {} {}", id, buildInfoModel.getName(), autoExecCron);
        CronUtils.upsert(taskId, autoExecCron, new CommandService.CronTask(id));
        return true;
    }

    /**
     * ????????????????????????
     */
    @Override
    public List<CommandModel> queryStartingList() {
        String sql = "select * from " + super.getTableName() + " where autoExecCron is not null and autoExecCron <> ''";
        return super.queryList(sql);
    }

    @Override
    public String typeName() {
        return getTableName();
    }

    @Override
    public List<Entity> allEntityTokens() {
        String sql = "select id,triggerToken from " + getTableName() + " where triggerToken is not null";
        return super.query(sql);
    }

    private class CronTask implements Task {

        private final String id;

        public CronTask(String id) {
            this.id = id;
        }

        @Override
        public void execute() {
            try {
                BaseServerController.resetInfo(UserModel.EMPTY);
                CommandModel commandModel = CommandService.this.getByKey(this.id);
                CommandService.this.executeBatch(commandModel, commandModel.getDefParams(), commandModel.getSshIds(), 1);
            } catch (Exception e) {
                log.error("????????????????????????????????????", e);
            } finally {
                BaseServerController.removeEmpty();
            }
        }
    }

    /**
     * ??????????????????
     *
     * @param id     ?????? id
     * @param nodes  ssh??????
     * @param params ??????
     * @return ??????ID
     */
    public String executeBatch(String id, String params, String nodes) {
        CommandModel commandModel = this.getByKey(id);
        return this.executeBatch(commandModel, params, nodes, 0);
    }

    /**
     * ??????????????????
     *
     * @param commandModel ????????????
     * @param nodes        ssh??????
     * @param params       ??????
     * @return ??????ID
     */
    public String executeBatch(CommandModel commandModel, String params, String nodes, int triggerExecType) {
        Assert.notNull(commandModel, "?????????????????????");
        List<CommandModel.CommandParam> commandParams = CommandModel.params(params);
        List<String> sshIds = StrUtil.split(nodes, StrUtil.COMMA, true, true);
        Assert.notEmpty(sshIds, "????????? ssh ??????");
        String batchId = IdUtil.fastSimpleUUID();
        for (String sshId : sshIds) {
            this.executeItem(commandModel, commandParams, sshId, batchId, triggerExecType);
        }
        return batchId;
    }

    /**
     * ???????????? ?????????
     *
     * @param commandModel  ????????????
     * @param commandParams ??????
     * @param sshId         ssh id
     * @param batchId       ??????ID
     */
    private void executeItem(CommandModel commandModel, List<CommandModel.CommandParam> commandParams, String sshId, String batchId, int triggerExecType) {
        SshModel sshModel = sshService.getByKey(sshId, false);

        CommandExecLogModel commandExecLogModel = new CommandExecLogModel();
        commandExecLogModel.setCommandId(commandModel.getId());
        commandExecLogModel.setCommandName(commandModel.getName());
        commandExecLogModel.setBatchId(batchId);
        commandExecLogModel.setSshId(sshId);
        commandExecLogModel.setWorkspaceId(commandModel.getWorkspaceId());
        commandExecLogModel.setTriggerExecType(triggerExecType);
        if (sshModel != null) {
            commandExecLogModel.setSshName(sshModel.getName());
        }
        commandExecLogModel.setStatus(CommandExecLogModel.Status.ING.getCode());
        // ????????????
        String commandParamsLine;
        if (commandParams != null) {
            commandExecLogModel.setParams(JSONObject.toJSONString(commandParams));
            commandParamsLine = commandParams.stream().map(CommandModel.CommandParam::getValue).collect(Collectors.joining(StrUtil.SPACE));
        } else {
            commandParamsLine = StrUtil.EMPTY;
        }
        commandExecLogService.insert(commandExecLogModel);

        ThreadUtil.execute(() -> {
            try {
                this.execute(commandModel, commandExecLogModel, sshModel, commandParamsLine);
            } catch (Exception e) {
                log.error("??????????????????????????????", e);
                this.updateStatus(commandExecLogModel.getId(), CommandExecLogModel.Status.SESSION_ERROR);
            }
        });

    }


    /**
     * ????????????
     *
     * @param commandModel        ????????????
     * @param commandExecLogModel ????????????
     * @param sshModel            ssh
     * @param commandParamsLine   ??????
     * @throws IOException io
     */
    private void execute(CommandModel commandModel, CommandExecLogModel commandExecLogModel, SshModel sshModel, String commandParamsLine) throws IOException {
        File file = commandExecLogModel.logFile();
        try (BufferedOutputStream outputStream = FileUtil.getOutputStream(file)) {
            if (sshModel == null) {
                this.appendLine(outputStream, "ssh ?????????");
                return;
            }
            String command = commandModel.getCommand();
            String[] commands = StrUtil.splitToArray(command, StrUtil.LF);
            //
            workspaceEnvVarService.formatCommand(commandModel.getWorkspaceId(), commands);
            //
            Charset charset = sshModel.charset();

            sshService.exec(sshModel, (s, session) -> {
                final ChannelExec channel = (ChannelExec) JschUtil.createChannel(session, ChannelType.EXEC);
                channel.setCommand(StrUtil.bytes(s + StrUtil.SPACE + commandParamsLine, charset));
                channel.setInputStream(null);

                channel.setErrStream(outputStream, true);
                InputStream in = null;

                try {
                    channel.connect(sshModel.timeout());
                    in = channel.getInputStream();
                    IoUtil.readLines(in, charset, (LineHandler) line -> this.appendLine(outputStream, line));
                    // ????????????
                    this.updateStatus(commandExecLogModel.getId(), CommandExecLogModel.Status.DONE);
                } catch (Exception e) {
                    log.error("??????????????????", e);
                    // ????????????
                    this.updateStatus(commandExecLogModel.getId(), CommandExecLogModel.Status.ERROR);
                    // ??????????????????
                    String stacktraceToString = ExceptionUtil.stacktraceToString(e);
                    this.appendLine(outputStream, stacktraceToString);
                } finally {
                    IoUtil.close(in);
                    JschUtil.close(channel);
                }
                return null;
            }, commands);
        }
    }

    /**
     * ??????????????????
     *
     * @param id     ID
     * @param status ??????
     */
    private void updateStatus(String id, CommandExecLogModel.Status status) {
        CommandExecLogModel commandExecLogModel = new CommandExecLogModel();
        commandExecLogModel.setId(id);
        commandExecLogModel.setStatus(status.getCode());
        commandExecLogService.update(commandExecLogModel);
    }

    /**
     * ????????????
     *
     * @param outputStream ???????????????
     * @param line         ??????
     */
    private void appendLine(BufferedOutputStream outputStream, String line) {
        try {
            outputStream.write(line.getBytes(CharsetUtil.CHARSET_UTF_8));
            outputStream.write(LINE_BYTES);
            outputStream.flush();
        } catch (IOException e) {
            log.warn("command log append line:{}", e.getMessage());
        }
    }

    /**
     * ???ssh ???????????????????????????????????????
     *
     * @param ids            ????????????ID
     * @param nowWorkspaceId ?????????????????????ID
     * @param workspaceId    ???????????????????????????
     */
    public void syncToWorkspace(String ids, String nowWorkspaceId, String workspaceId) {
        StrUtil.splitTrim(ids, StrUtil.COMMA)
            .forEach(id -> {
                CommandModel data = super.getByKey(id, false, entity -> entity.set("workspaceId", nowWorkspaceId));
                Assert.notNull(data, "???????????????ssh????????????");
                //
                CommandModel where = new CommandModel();
                where.setWorkspaceId(workspaceId);
                where.setName(data.getName());
                CommandModel exits = super.queryByBean(where);
                if (exits == null) {
                    // ?????????????????? ??????
                    data.setId(null);
                    data.setWorkspaceId(workspaceId);
                    data.setCreateTimeMillis(null);
                    data.setModifyTimeMillis(null);
                    data.setSshIds(null);
                    data.setModifyUser(null);
                    super.insert(data);
                } else {
                    // ????????????
                    CommandModel update = new CommandModel();
                    update.setId(exits.getId());
                    update.setCommand(data.getCommand());
                    update.setDesc(data.getDesc());
                    update.setType(data.getType());
                    update.setDefParams(data.getDefParams());
                    update.setAutoExecCron(data.getAutoExecCron());
                    super.updateById(update);
                }
            });
    }

}
