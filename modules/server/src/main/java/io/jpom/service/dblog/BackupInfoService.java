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
package io.jpom.service.dblog;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.*;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.db.Entity;
import cn.hutool.db.sql.Direction;
import cn.hutool.db.sql.Order;
import io.jpom.common.BaseServerController;
import io.jpom.common.Const;
import io.jpom.common.JpomManifest;
import io.jpom.common.ServerConst;
import io.jpom.model.data.BackupInfoModel;
import io.jpom.model.enums.BackupStatusEnum;
import io.jpom.model.enums.BackupTypeEnum;
import io.jpom.model.user.UserModel;
import io.jpom.plugin.IPlugin;
import io.jpom.plugin.PluginFactory;
import io.jpom.service.h2db.BaseDbService;
import io.jpom.system.db.DbConfig;
import io.jpom.system.extconf.DbExtConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ??????????????? service
 *
 * @author Hotstrip
 * @since 2021-11-18
 **/
@Service
@Slf4j
public class BackupInfoService extends BaseDbService<BackupInfoModel> {

    private final DbExtConfig dbExtConfig;

    public BackupInfoService(DbExtConfig dbExtConfig) {
        this.dbExtConfig = dbExtConfig;
    }

    /**
     * ?????????????????????
     */
    public void checkAutoBackup() {
        try {
            BaseServerController.resetInfo(UserModel.EMPTY);
            // ????????????
            this.createAutoBackup();
            // ??????????????????
            this.deleteAutoBackup();
        } finally {
            BaseServerController.removeEmpty();
        }
    }

    /**
     * ???????????? ??????????????????
     */
    private void deleteAutoBackup() {
        Integer autoBackupReserveDay = dbExtConfig.getAutoBackupReserveDay();
        if (autoBackupReserveDay != null && autoBackupReserveDay > 0) {
            //
            Entity entity = Entity.create();
            entity.set("backupType", 3);
            entity.set("createTimeMillis", " < " + (SystemClock.now() - TimeUnit.DAYS.toMillis(autoBackupReserveDay)));
            List<Entity> entities = super.queryList(entity);
            if (entities != null) {
                for (Entity entity1 : entities) {
                    String id = entity1.getStr("id");
                    this.delByKey(id);
                }
            }
        }
    }

    /**
     * ????????????????????????
     */
    private void createAutoBackup() {
        // ????????????
        Integer autoBackupIntervalDay = dbExtConfig.getAutoBackupIntervalDay();
        if (autoBackupIntervalDay != null && autoBackupIntervalDay > 0) {
            BackupInfoModel backupInfoModel = new BackupInfoModel();
            backupInfoModel.setBackupType(3);
            List<BackupInfoModel> infoModels = super.queryList(backupInfoModel, 1, new Order("createTimeMillis", Direction.DESC));
            BackupInfoModel first = CollUtil.getFirst(infoModels);
            if (first != null) {
                Long createTimeMillis = first.getCreateTimeMillis();
                long interval = SystemClock.now() - createTimeMillis;
                if (interval < TimeUnit.DAYS.toMillis(autoBackupIntervalDay)) {
                    return;
                }
            }
            this.autoBackup();
        }
    }

    /**
     * ????????????
     */
    public Future<BackupInfoModel> autoBackup() {
        // ?????????????????????
        return this.backupToSql(null, BackupTypeEnum.AUTO);
    }

    /**
     * ??????????????? SQL ??????
     *
     * @param tableNameList ?????????????????????????????????????????????????????????????????????
     */
    public Future<BackupInfoModel> backupToSql(final List<String> tableNameList) {
        // ??????????????????
        BackupTypeEnum backupType = BackupTypeEnum.ALL;
        if (!CollectionUtils.isEmpty(tableNameList)) {
            backupType = BackupTypeEnum.PART;
        }
        return this.backupToSql(tableNameList, backupType);
    }

    /**
     * ??????????????? SQL ??????
     *
     * @param tableNameList ?????????????????????????????????????????????????????????????????????
     */
    private Future<BackupInfoModel> backupToSql(final List<String> tableNameList, BackupTypeEnum backupType) {
        final String fileName = LocalDateTimeUtil.format(LocalDateTimeUtil.now(), DatePattern.PURE_DATETIME_PATTERN);

        // ?????????????????? SQL ???????????????
        File file = FileUtil.file(DbConfig.getInstance().dbLocalPath(), ServerConst.BACKUP_DIRECTORY_NAME, fileName + ServerConst.SQL_FILE_SUFFIX);
        final String backupSqlPath = FileUtil.getAbsolutePath(file);

        // ???????????????
        final String url = DbConfig.getInstance().getDbUrl();

        final String user = dbExtConfig.getUserName();
        final String pass = dbExtConfig.getUserPwd();

        JpomManifest instance = JpomManifest.getInstance();
        // ????????????????????????????????????
        BackupInfoModel backupInfoModel = new BackupInfoModel();
        String timeStamp = instance.getTimeStamp();
        try {
            DateTime parse = DateUtil.parse(timeStamp);
            backupInfoModel.setBaleTimeStamp(parse.getTime());
        } catch (Exception ignored) {
        }
        backupInfoModel.setName(fileName);
        backupInfoModel.setVersion(instance.getVersion());
        backupInfoModel.setBackupType(backupType.getCode());
        backupInfoModel.setFilePath(backupSqlPath);
        this.insert(backupInfoModel);
        IPlugin plugin = PluginFactory.getPlugin("db-h2");
        // ?????????????????????????????????????????????????????????????????????????????????????????????
        return ThreadUtil.execAsync(() -> {
            // ?????????????????????
            BackupInfoModel backupInfo = new BackupInfoModel();
            BeanUtil.copyProperties(backupInfoModel, backupInfo);
            try {
                log.debug("start a new Thread to execute H2 Database backup...start");
                Map<String, Object> map = new HashMap<>(10);
                map.put("url", url);
                map.put("user", user);
                map.put("pass", pass);
                map.put("backupSqlPath", backupSqlPath);
                map.put("tableNameList", tableNameList);
                plugin.execute("backupSql", map);
                //h2BackupService.backupSql(url, user, pass, backupSqlPath, tableNameList);
                // ??????????????????????????????
                backupInfo.setFileSize(FileUtil.size(file));
                backupInfo.setSha1Sum(SecureUtil.sha1(file));
                backupInfo.setStatus(BackupStatusEnum.SUCCESS.getCode());
                this.update(backupInfo);
                log.debug("start a new Thread to execute H2 Database backup...success");
            } catch (Exception e) {
                // ?????????????????????????????????????????????????????????
                log.error("start a new Thread to execute H2 Database backup...catch exception...", e);
                backupInfo.setStatus(BackupStatusEnum.FAILED.getCode());
                this.update(backupInfo);
            }
            return backupInfo;
        });
    }

    /**
     * ?????? SQL ?????????????????????
     * ????????????????????????????????????????????????????????????????????????????????????
     *
     * @param backupSqlPath ?????? sql ????????????
     */
    public boolean restoreWithSql(String backupSqlPath) {
        try {
            long startTs = System.currentTimeMillis();
            IPlugin plugin = PluginFactory.getPlugin("db-h2");
            Map<String, Object> map = new HashMap<>(10);
            map.put("backupSqlPath", backupSqlPath);
            plugin.execute("restoreBackupSql", map);
            // h2BackupService.restoreBackupSql(backupSqlPath);
            long endTs = System.currentTimeMillis();
            log.debug("restore H2 Database backup...success...cast {} ms", endTs - startTs);
            return true;
        } catch (Exception e) {
            // ??????????????????????????????????????????????????????????????????
            log.error("restore H2 Database backup...catch exception...message: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * load table name list from h2 database
     *
     * @return list
     */
    public List<String> h2TableNameList() {
        String sql = "show tables;";
        List<Entity> list = super.query(sql);
        // ????????????
        return list.stream()
            .filter(entity -> StringUtils.hasLength(String.valueOf(entity.get(ServerConst.TABLE_NAME))))
            .flatMap(entity -> Stream.of(String.valueOf(entity.get(ServerConst.TABLE_NAME))))
            .distinct()
            .collect(Collectors.toList());
    }

    @Override
    public int delByKey(String keyValue) {
        // ?????? id ??????????????????
        BackupInfoModel backupInfoModel = super.getByKey(keyValue);
        Objects.requireNonNull(backupInfoModel, "?????????????????????");

        // ?????????????????????
        boolean del = FileUtil.del(backupInfoModel.getFilePath());
        Assert.state(del, "??????????????????????????????");

        // ??????????????????
        return super.delByKey(keyValue);
    }
}
