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
package io.jpom.service.h2db;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.SystemClock;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.*;
import cn.hutool.db.Entity;
import cn.hutool.db.Page;
import cn.hutool.db.sql.Direction;
import cn.hutool.db.sql.Order;
import cn.hutool.extra.servlet.ServletUtil;
import cn.jiangzeyin.common.spring.SpringUtil;
import io.jpom.common.BaseServerController;
import io.jpom.common.ServerConst;
import io.jpom.model.BaseDbModel;
import io.jpom.model.BaseUserModifyDbModel;
import io.jpom.model.PageResultDto;
import io.jpom.model.user.UserModel;
import io.jpom.system.extconf.DbExtConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * ??????????????? ?????? serve
 *
 * @author bwcx_jzy
 * @since 2021/8/13
 */
@Slf4j
public abstract class BaseDbService<T extends BaseDbModel> extends BaseDbCommonService<T> {

    /**
     * ??????????????????
     */
    private static final Order[] DEFAULT_ORDERS = new Order[]{
        new Order("createTimeMillis", Direction.DESC),
        new Order("modifyTimeMillis", Direction.DESC)
    };

    public BaseDbService() {
        super(null, ServerConst.ID_STR);
    }

    @Override
    protected String covetTableName(String tableName, Class<T> tClass) {
        TableName annotation = tClass.getAnnotation(TableName.class);
        Assert.notNull(annotation, "????????? table Name");
        return annotation.value();
    }

    @Override
    public void insert(T t) {
        this.fillInsert(t);
        super.insert(t);
        this.executeClear();
    }

    /**
     * ????????? ???????????????????????????
     *
     * @param t ??????
     */
    public void upsert(T t) {
        int update = this.update(t);
        if (update <= 0) {
            this.insert(t);
        }
    }

    /**
     * ????????? ??????
     *
     * @param t ??????
     */
    public void insertNotFill(T t) {
        // def create time
        t.setCreateTimeMillis(ObjectUtil.defaultIfNull(t.getCreateTimeMillis(), SystemClock.now()));
        t.setId(ObjectUtil.defaultIfNull(t.getId(), IdUtil.fastSimpleUUID()));
        super.insert(t);
    }

    @Override
    public void insert(Collection<T> t) {
        // def create time
        t.forEach(this::fillInsert);
        super.insert(t);
        this.executeClear();
    }

    /**
     * ??????????????????
     *
     * @param t ??????
     */
    protected void fillInsert(T t) {
        // def create time
        t.setCreateTimeMillis(ObjectUtil.defaultIfNull(t.getCreateTimeMillis(), SystemClock.now()));
        t.setId(StrUtil.emptyToDefault(t.getId(), IdUtil.fastSimpleUUID()));
        if (t instanceof BaseUserModifyDbModel) {
            // ?????????????????????
            BaseUserModifyDbModel modifyDbModel = (BaseUserModifyDbModel) t;
            if (StrUtil.isEmpty(modifyDbModel.getModifyUser())) {
                UserModel userModel = BaseServerController.getUserModel();
                userModel = userModel == null ? BaseServerController.getUserByThreadLocal() : userModel;
                if (userModel != null) {
                    modifyDbModel.setModifyUser(ObjectUtil.defaultIfNull(modifyDbModel.getModifyUser(), userModel.getId()));
                }
            }
        }
    }

    /**
     * update by id with data
     *
     * @param info          data
     * @param whereConsumer ??????????????????
     * @return ???????????????
     */
    public int updateById(T info, Consumer<Entity> whereConsumer) {
        // check id
        String id = info.getId();
        Assert.hasText(id, "???????????????error");
        // def modify time
        info.setModifyTimeMillis(ObjectUtil.defaultIfNull(info.getModifyTimeMillis(), SystemClock.now()));
        // remove create time
        Long createTimeMillis = info.getCreateTimeMillis();
        info.setCreateTimeMillis(null);
        // fill modify user
        if (info instanceof BaseUserModifyDbModel) {
            BaseUserModifyDbModel modifyDbModel = (BaseUserModifyDbModel) info;
            UserModel userModel = BaseServerController.getUserModel();
            if (userModel != null) {
                modifyDbModel.setModifyUser(ObjectUtil.defaultIfNull(modifyDbModel.getModifyUser(), userModel.getId()));
            }
        }
        //
        Entity entity = this.dataBeanToEntity(info);
        //
        entity.remove(StrUtil.format("`{}`", ServerConst.ID_STR));
        //
        Entity where = new Entity();
        where.set(ServerConst.ID_STR, id);
        if (whereConsumer != null) {
            whereConsumer.accept(where);
        }
        int update = super.update(entity, where);
        // backtrack
        info.setCreateTimeMillis(createTimeMillis);
        return update;
    }

    /**
     * update by id with data
     *
     * @param info data
     * @return ???????????????
     */
    public int updateById(T info) {
        return this.updateById(info, null);
    }

    @Override
    public int update(T t) {
        return this.updateById(t);
    }

    public List<T> list() {
        return super.listByBean(ReflectUtil.newInstance(this.tClass));
    }

    public long count() {
        return super.count(Entity.create());
    }

    public long count(T data) {
        return super.count(this.dataBeanToEntity(data));
    }

    /**
     * ?????????????????????, ?????????????????????????????????????????????????????? "page", "limit", "order_field", "order", "total"
     * <p>
     * page=1&limit=10&order=ascend&order_field=name
     *
     * @param request ????????????
     * @return page
     */
    public PageResultDto<T> listPage(HttpServletRequest request) {
        Map<String, String> paramMap = ServletUtil.getParamMap(request);
        return this.listPage(paramMap);
    }

    /**
     * ????????? page ??????
     *
     * @param paramMap ????????????
     * @return page
     */
    public Page parsePage(Map<String, String> paramMap) {
        int page = Convert.toInt(paramMap.get("page"), 1);
        int limit = Convert.toInt(paramMap.get("limit"), 10);
        Assert.state(page > 0, "page value error");
        Assert.state(limit > 0 && limit < 200, "limit value error");
        // ?????? ????????????
        MapUtil.removeAny(paramMap, "page", "limit", "order_field", "order", "total");
        //
        return new Page(page, limit);
    }

    /**
     * ?????????????????????, ?????????????????????????????????????????????????????? "page", "limit", "order_field", "order", "total"
     * <p>
     * page=1&limit=10&order=ascend&order_field=name
     *
     * @param paramMap ????????????
     * @return page
     */
    public PageResultDto<T> listPage(Map<String, String> paramMap) {
        String orderField = paramMap.get("order_field");
        String order = paramMap.get("order");
        //
        Page pageReq = this.parsePage(paramMap);
        Entity where = Entity.create();
        List<String> ignoreField = new ArrayList<>(10);
        // ????????????
        for (Map.Entry<String, String> stringStringEntry : paramMap.entrySet()) {
            String key = stringStringEntry.getKey();
            String value = stringStringEntry.getValue();
            if (StrUtil.isEmpty(value)) {
                continue;
            }
            key = StrUtil.removeAll(key, "%");
            if (StrUtil.startWith(stringStringEntry.getKey(), "%") && StrUtil.endWith(stringStringEntry.getKey(), "%")) {
                where.set(StrUtil.format("`{}`", key), StrUtil.format(" like '%{}%'", value));
            } else if (StrUtil.endWith(stringStringEntry.getKey(), "%")) {
                where.set(StrUtil.format("`{}`", key), StrUtil.format(" like '{}%'", value));
            } else if (StrUtil.startWith(stringStringEntry.getKey(), "%")) {
                where.set(StrUtil.format("`{}`", key), StrUtil.format(" like '%{}'", value));
            } else if (StrUtil.containsIgnoreCase(key, "time") && StrUtil.contains(value, "~")) {
                // ????????????
                String[] val = StrUtil.splitToArray(value, "~");
                if (val.length == 2) {
                    DateTime startDateTime = DateUtil.parse(val[0], DatePattern.NORM_DATETIME_FORMAT);
                    where.set(key, ">= " + startDateTime.getTime());

                    DateTime endDateTime = DateUtil.parse(val[1], DatePattern.NORM_DATETIME_FORMAT);
                    if (startDateTime.equals(endDateTime)) {
                        endDateTime = DateUtil.endOfDay(endDateTime);
                    }
                    // ??????????????????
                    where.set(key + " ", "<= " + endDateTime.getTime());
                }
            } else if (StrUtil.containsIgnoreCase(key, "time")) {
                // ????????????
                String timeKey = StrUtil.removeAny(key, "[0]", "[1]");
                if (ignoreField.contains(timeKey)) {
                    continue;
                }
                String startTime = paramMap.get(timeKey + "[0]");
                String endTime = paramMap.get(timeKey + "[1]");
                if (StrUtil.isAllNotEmpty(startTime, endTime)) {
                    DateTime startDateTime = DateUtil.parse(startTime, DatePattern.NORM_DATETIME_FORMAT);
                    where.set(timeKey, ">= " + startDateTime.getTime());

                    DateTime endDateTime = DateUtil.parse(endTime, DatePattern.NORM_DATETIME_FORMAT);
                    if (startDateTime.equals(endDateTime)) {
                        endDateTime = DateUtil.endOfDay(endDateTime);
                    }
                    // ??????????????????
                    where.set(timeKey + " ", "<= " + endDateTime.getTime());
                }
                ignoreField.add(timeKey);
            } else if (StrUtil.endWith(key, ":in")) {
                String inKey = StrUtil.removeSuffix(key, ":in");
                where.set(StrUtil.format("`{}`", inKey), StrUtil.split(value, StrUtil.COMMA));
            } else {
                where.set(StrUtil.format("`{}`", key), value);
            }
        }
        // ??????
        if (StrUtil.isNotEmpty(orderField)) {
            orderField = StrUtil.removeAll(orderField, "%");
            pageReq.addOrder(new Order(orderField, StrUtil.equalsIgnoreCase(order, "ascend") ? Direction.ASC : Direction.DESC));
        }
        return this.listPage(where, pageReq);
    }

    @Override
    public PageResultDto<T> listPage(Entity where, Page page) {
        if (ArrayUtil.isEmpty(page.getOrders())) {
            //page.addOrder(new Order("createTimeMillis", Direction.DESC));
            //page.addOrder(new Order("modifyTimeMillis", Direction.DESC));
            page.addOrder(this.defaultOrders());
        }
        return super.listPage(where, page);
    }

    protected Order[] defaultOrders() {
        return DEFAULT_ORDERS;
    }

    /**
     * ?????? id ????????????
     *
     * @param ids ids
     * @return list
     */
    public List<T> listById(Collection<String> ids) {
        return this.listById(ids, null);
    }

    /**
     * ?????? id ????????????
     *
     * @param ids ids
     * @return list
     */
    public List<T> listById(Collection<String> ids, Consumer<Entity> consumer) {
        if (CollUtil.isEmpty(ids)) {
            return null;
        }
        Entity entity = Entity.create();
        entity.set(ServerConst.ID_STR, ids);
        if (consumer != null) {
            consumer.accept(entity);
        }
        List<Entity> entities = super.queryList(entity);
        return this.entityToBeanList(entities);
    }

    /**
     * ????????????
     */
    private void executeClear() {
        DbExtConfig dbExtConfig = SpringUtil.getBean(DbExtConfig.class);
        int h2DbLogStorageCount = dbExtConfig.getLogStorageCount();
        if (h2DbLogStorageCount <= 0) {
            return;
        }
        this.executeClearImpl(h2DbLogStorageCount);
    }

    /**
     * ??????????????????
     *
     * @param h2DbLogStorageCount ????????????
     */
    protected void executeClearImpl(int h2DbLogStorageCount) {
        String[] strings = this.clearTimeColumns();
        for (String timeColumn : strings) {
            this.autoClear(timeColumn, h2DbLogStorageCount, time -> {
                Entity entity = Entity.create(super.getTableName());
                entity.set(timeColumn, "< " + time);
                int count = super.del(entity);
                if (count > 0) {
                    log.debug("{} ????????? {}?????????", super.getTableName(), count);
                }
            });
        }
    }

    /**
     * ???????????????????????????????????????
     *
     * @return ??????
     */
    protected String[] clearTimeColumns() {
        return new String[]{};
    }

    /**
     * ????????????????????????
     *
     * @param timeColumn ????????????
     * @param maxCount   ????????????
     * @param consumer   ????????????????????????????????????
     */
    protected void autoClear(String timeColumn, int maxCount, Consumer<Long> consumer) {
        if (maxCount <= 0) {
            return;
        }
        ThreadUtil.execute(() -> {
            long timeValue = this.getLastTimeValue(timeColumn, maxCount, null);
            if (timeValue <= 0) {
                return;
            }
            consumer.accept(timeValue);
        });
    }

    /**
     * ???????????????????????? ??????????????????????????????
     *
     * @param timeColumn ????????????
     * @param maxCount   ????????????
     * @param whereCon   ????????????????????????
     * @return ??????
     */
    protected long getLastTimeValue(String timeColumn, int maxCount, Consumer<Entity> whereCon) {
        Entity entity = Entity.create(super.getTableName());
        if (whereCon != null) {
            // ??????
            whereCon.accept(entity);
        }
        Page page = new Page(maxCount, 1);
        page.addOrder(new Order(timeColumn, Direction.DESC));
        PageResultDto<T> pageResult;
        try {
            pageResult = super.listPage(entity, page);
        } catch (java.lang.IllegalStateException illegalStateException) {
            return 0L;
        } catch (Exception e) {
            log.error("??????????????????", e);
            return 0L;
        }
        if (pageResult.isEmpty()) {
            return 0L;
        }
        T entity1 = pageResult.get(0);
        Object fieldValue = ReflectUtil.getFieldValue(entity1, timeColumn);
        return Convert.toLong(fieldValue, 0L);
    }

    /**
     * ????????????????????????
     *
     * @param timeClo   ????????????
     * @param maxCount  ????????????
     * @param predicate ??????????????????????????????,??????
     */
    protected void autoLoopClear(String timeClo, int maxCount, Consumer<Entity> whereCon, Predicate<T> predicate) {
        if (maxCount <= 0) {
            return;
        }
        ThreadUtil.execute(() -> {
            Entity entity = Entity.create(super.getTableName());
            long timeValue = this.getLastTimeValue(timeClo, maxCount, whereCon);
            if (timeValue <= 0) {
                return;
            }
            if (whereCon != null) {
                // ??????
                whereCon.accept(entity);
            }
            entity.set(timeClo, "< " + timeValue);
            while (true) {
                Page page = new Page(1, 50);
                page.addOrder(new Order(timeClo, Direction.DESC));
                PageResultDto<T> pageResult = super.listPage(entity, page);
                if (pageResult.isEmpty()) {
                    return;
                }
//                pageResult.each(consumer);
                List<String> ids = pageResult.getResult().stream().filter(predicate).map(BaseDbModel::getId).collect(Collectors.toList());
                super.delByKey(ids, null);
            }
        });
    }

    /**
     * ?????? ???????????????ID????????????
     *
     * @param nodeId ??????ID
     * @param dataId ??????ID
     * @return data
     */
    public T getData(String nodeId, String dataId) {
        return super.getByKey(dataId);
    }
}
