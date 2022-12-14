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
package io.jpom.permission;

import io.jpom.service.dblog.*;
import io.jpom.service.docker.DockerInfoService;
import io.jpom.service.docker.DockerSwarmInfoService;
import io.jpom.service.h2db.BaseDbService;
import io.jpom.service.monitor.MonitorService;
import io.jpom.service.monitor.MonitorUserOptService;
import io.jpom.service.node.NodeService;
import io.jpom.service.node.ProjectInfoCacheService;
import io.jpom.service.node.command.CommandExecLogService;
import io.jpom.service.node.command.CommandService;
import io.jpom.service.node.script.NodeScriptExecuteLogServer;
import io.jpom.service.node.script.NodeScriptServer;
import io.jpom.service.node.ssh.SshService;
import io.jpom.service.outgiving.DbOutGivingLogService;
import io.jpom.service.outgiving.LogReadServer;
import io.jpom.service.outgiving.OutGivingServer;
import io.jpom.service.script.ScriptExecuteLogServer;
import io.jpom.service.script.ScriptServer;
import io.jpom.service.system.WorkspaceService;
import io.jpom.service.user.UserPermissionGroupServer;
import io.jpom.service.user.UserService;
import lombok.Getter;

/**
 * ????????????
 *
 * @author bwcx_jzy
 * @since 2019/8/13
 */
@Getter
public enum ClassFeature {
    /**
     * ??????
     */
    NULL("", null, null),
    NODE("????????????", NodeService.class),
    NODE_STAT("????????????", NodeService.class),
    UPGRADE_NODE_LIST("????????????", NodeService.class),
    SEARCH_PROJECT("????????????", ProjectInfoCacheService.class),
    SSH("SSH??????", SshService.class),
    SSH_FILE("SSH????????????", SshService.class),
    SSH_TERMINAL("SSH??????", SshService.class),
    SSH_TERMINAL_LOG("SSH????????????", SshTerminalExecuteLogService.class),
    SSH_COMMAND("SSH????????????", CommandService.class),
    SSH_COMMAND_LOG("SSH????????????", CommandExecLogService.class),
    OUTGIVING("????????????", OutGivingServer.class),
    LOG_READ("????????????", LogReadServer.class),
    OUTGIVING_LOG("????????????", DbOutGivingLogService.class),
    OUTGIVING_CONFIG_WHITELIST("?????????????????????"),
    MONITOR("????????????", MonitorService.class),
    MONITOR_LOG("????????????", DbMonitorNotifyLogService.class),
    OPT_MONITOR("????????????", MonitorUserOptService.class),
    DOCKER("Docker??????", DockerInfoService.class),
    DOCKER_SWARM("????????????", DockerSwarmInfoService.class),
    /**
     * ssh
     */
    BUILD("????????????", BuildInfoService.class),
    BUILD_LOG("????????????", DbBuildHistoryLogService.class),
    BUILD_REPOSITORY("????????????", RepositoryService.class),
    USER("????????????", UserService.class),
    USER_LOG("????????????", DbUserOperateLogService.class),
    USER_PERMISSION_GROUP("????????????", UserPermissionGroupServer.class),
    SYSTEM_EMAIL("????????????"),
    SYSTEM_CACHE("????????????"),
    SYSTEM_LOG("????????????"),
    SYSTEM_UPGRADE("????????????"),
    SYSTEM_CONFIG("?????????????????????"),
    BUILD_CONFIG("????????????"),
    SYSTEM_CONFIG_IP("????????????IP?????????"),
    SYSTEM_CONFIG_MENUS("??????????????????"),
    SYSTEM_NODE_WHITELIST("?????????????????????"),
    SYSTEM_BACKUP("???????????????", BackupInfoService.class),
    SYSTEM_WORKSPACE("????????????", WorkspaceService.class),

    SCRIPT("????????????", ScriptServer.class),
    SCRIPT_LOG("??????????????????", ScriptExecuteLogServer.class),

    //******************************************     ??????????????????
    PROJECT("????????????", ClassFeature.NODE, ProjectInfoCacheService.class),
    PROJECT_FILE("??????????????????", ClassFeature.NODE, ProjectInfoCacheService.class),
    PROJECT_LOG("????????????", ClassFeature.NODE, ProjectInfoCacheService.class),
    PROJECT_CONSOLE("???????????????", ClassFeature.NODE, ProjectInfoCacheService.class),
    JDK_LIST("JDK??????", ClassFeature.NODE),
    NODE_SCRIPT("??????????????????", ClassFeature.NODE, NodeScriptServer.class),
    NODE_SCRIPT_LOG("????????????????????????", ClassFeature.NODE, NodeScriptExecuteLogServer.class),
    TOMCAT("Tomcat", ClassFeature.NODE),
    TOMCAT_FILE("Tomcat file", ClassFeature.NODE),
    TOMCAT_LOG("Tomcat log", ClassFeature.NODE),

    NGINX("Nginx", ClassFeature.NODE),
    SSL("ssl??????", ClassFeature.NODE),
    NODE_CONFIG_WHITELIST("?????????????????????", ClassFeature.NODE),
    NODE_CONFIG("?????????????????????", ClassFeature.NODE),
    NODE_CACHE("????????????", ClassFeature.NODE),
    NODE_LOG("??????????????????", ClassFeature.NODE),
    NODE_UPGRADE("??????????????????", ClassFeature.NODE),


//	PROJECT_RECOVER("????????????", ClassFeature.NODE),

    ;

    private final String name;
    private final ClassFeature parent;
    private final Class<? extends BaseDbService<?>> dbService;

    ClassFeature(String name) {
        this(name, null, null);
    }

    ClassFeature(String name, ClassFeature parent) {
        this(name, parent, null);
    }


    ClassFeature(String name, Class<? extends BaseDbService<?>> dbService) {
        this(name, null, dbService);
    }

    ClassFeature(String name, ClassFeature parent, Class<? extends BaseDbService<?>> dbService) {
        this.name = name;
        this.parent = parent;
        this.dbService = dbService;
    }
}
