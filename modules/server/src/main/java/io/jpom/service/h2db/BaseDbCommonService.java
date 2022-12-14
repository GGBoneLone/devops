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

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.util.PageUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.TypeUtil;
import cn.hutool.db.Db;
import cn.hutool.db.Entity;
import cn.hutool.db.Page;
import cn.hutool.db.PageResult;
import cn.hutool.db.sql.Condition;
import cn.hutool.db.sql.Order;
import io.jpom.model.PageResultDto;
import io.jpom.system.JpomRuntimeException;
import io.jpom.system.db.DbConfig;
import lombok.extern.slf4j.Slf4j;
import org.h2.jdbc.JdbcSQLNonTransientConnectionException;
import org.h2.jdbc.JdbcSQLNonTransientException;
import org.h2.mvstore.MVStoreException;
import org.springframework.util.Assert;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * ????????????????????? ?????? service
 *
 * @author bwcx_jzy
 * @since 2019/7/20
 */
@Slf4j
public abstract class BaseDbCommonService<T> {

    static {
        // ?????????????????? 1 ??????
        PageUtil.setFirstPageNo(1);
    }

    /**
     * ??????
     */
    protected final String tableName;
    protected final Class<T> tClass;
    /**
     * ??????
     */
    protected final String key;

    @SuppressWarnings("unchecked")
    public BaseDbCommonService(String tableName, String key) {
        this.tClass = (Class<T>) TypeUtil.getTypeArgument(this.getClass());
        this.tableName = this.covetTableName(tableName, this.tClass);
        this.key = key;
    }

    /**
     * ????????????
     *
     * @param tableName ??????
     * @param tClass    ???
     * @return ??????????????????
     */
    protected String covetTableName(String tableName, Class<T> tClass) {
        return tableName;
    }

    public String getTableName() {
        return tableName;
    }

    protected String getKey() {
        return key;
    }

    /**
     * ????????????
     *
     * @param t ??????
     */
    public void insert(T t) {
        if (!DbConfig.getInstance().isInit()) {
            // ignore
            log.error("The database is not initialized, this execution will be ignored:{},{}", this.tClass, this.getClass());
            return;
        }
        Db db = Db.use();
        db.setWrapper((Character) null);
        try {
            Entity entity = this.dataBeanToEntity(t);
            db.insert(entity);
        } catch (Exception e) {
            throw warpException(e);
        }
    }

    /**
     * ????????????
     *
     * @param t ??????
     */
    public void insert(Collection<T> t) {
        if (CollUtil.isEmpty(t)) {
            return;
        }
        if (!DbConfig.getInstance().isInit()) {
            // ignore
            log.error("The database is not initialized, this execution will be ignored:{},{}", this.tClass, this.getClass());
            return;
        }
        Db db = Db.use();
        db.setWrapper((Character) null);
        try {
            List<Entity> entities = t.stream().map(this::dataBeanToEntity).collect(Collectors.toList());
            db.insert(entities);
        } catch (Exception e) {
            throw warpException(e);
        }
    }

    /**
     * ????????? entity
     *
     * @param data ????????????
     * @return entity
     */
    public Entity dataBeanToEntity(T data) {
        Entity entity = new Entity(tableName);
        // ????????? map
        Map<String, Object> beanToMap = BeanUtil.beanToMap(data, new LinkedHashMap<>(), true, s -> StrUtil.format("`{}`", s));
        entity.putAll(beanToMap);
        return entity;
    }

    /**
     * ????????????
     *
     * @param entity ??????????????????
     * @return ????????????
     */
    public int insert(Entity entity) {
        if (!DbConfig.getInstance().isInit()) {
            // ignore
            log.error("The database is not initialized, this execution will be ignored:{},{}", this.tClass, this.getClass());
            return 0;
        }
        Db db = Db.use();
        db.setWrapper((Character) null);
        entity.setTableName(tableName);
        try {
            return db.insert(entity);
        } catch (Exception e) {
            throw warpException(e);
        }
    }

    /**
     * ?????????????????????????????????
     *
     * @param t ??????
     * @return ????????????
     */
    public int update(T t) {
        return 0;
    }

    /**
     * ????????????
     *
     * @param entity ??????????????????
     * @param where  ??????
     * @return ????????????
     */
    public int update(Entity entity, Entity where) {
        if (!DbConfig.getInstance().isInit()) {
            // ignore
            log.error("The database is not initialized, this execution will be ignored:{},{}", this.tClass, this.getClass());
            return 0;
        }
        Db db = Db.use();
        db.setWrapper((Character) null);
        if (where.isEmpty()) {
            throw new JpomRuntimeException("??????????????????");
        }
        entity.setTableName(tableName);
        where.setTableName(tableName);
        try {
            return db.update(entity, where);
        } catch (Exception e) {
            throw warpException(e);
        }
    }

    /**
     * ????????????????????????
     *
     * @param keyValue ?????????
     * @return ??????
     */
    public T getByKey(String keyValue) {
        return this.getByKey(keyValue, true);
    }

    /**
     * ????????????????????????
     *
     * @param keyValue ?????????
     * @return ??????
     */
    public T getByKey(String keyValue, boolean fill) {
        return this.getByKey(keyValue, fill, null);
    }

    /**
     * ????????????????????????
     *
     * @param keyValue ?????????
     * @param fill     ????????????????????????
     * @param consumer ????????????
     * @return ??????
     */
    public T getByKey(String keyValue, boolean fill, Consumer<Entity> consumer) {
        if (StrUtil.isEmpty(keyValue)) {
            return null;
        }
        if (!DbConfig.getInstance().isInit()) {
            // ignore
            log.error("The database is not initialized, this execution will be ignored:{},{}", this.tClass, this.getClass());
            return null;
        }
        Entity where = new Entity(tableName);
        where.set(key, keyValue);
        Entity entity;
        try {
            Db db = Db.use();
            db.setWrapper((Character) null);
            if (consumer != null) {
                consumer.accept(where);
            }
            entity = db.get(where);
        } catch (Exception e) {
            throw warpException(e);
        }
        T entityToBean = this.entityToBean(entity, this.tClass);
        if (fill) {
            this.fillSelectResult(entityToBean);
        }
        return entityToBean;
    }

    /**
     * entity ??? ??????
     *
     * @param entity Entity
     * @param rClass ?????????
     * @param <R>    ??????
     * @return data
     */
    protected <R> R entityToBean(Entity entity, Class<R> rClass) {
        if (entity == null) {
            return null;
        }
        CopyOptions copyOptions = new CopyOptions();
        copyOptions.setIgnoreError(true);
        copyOptions.setIgnoreCase(true);
        return BeanUtil.toBean(entity, rClass, copyOptions);
    }

    /**
     * entity ??? ??????
     *
     * @param entity Entity
     * @return data
     */
    public T entityToBean(Entity entity) {
        if (entity == null) {
            return null;
        }
        CopyOptions copyOptions = new CopyOptions();
        copyOptions.setIgnoreError(true);
        copyOptions.setIgnoreCase(true);
        T toBean = BeanUtil.toBean(entity, this.tClass, copyOptions);
        this.fillSelectResult(toBean);
        return toBean;
    }

    /**
     * ??????????????????
     *
     * @param keyValue ?????????
     * @return ????????????
     */
    public int delByKey(String keyValue) {
        return this.delByKey(keyValue, null);
    }

    /**
     * ??????????????????
     *
     * @param keyValue ?????????
     * @param consumer ??????
     * @return ????????????
     */
    public int delByKey(Object keyValue, Consumer<Entity> consumer) {
        //		if (ObjectUtil.isEmpty(keyValue)) {
        //			return 0;
        //		}
        if (!DbConfig.getInstance().isInit()) {
            // ignore
            log.error("The database is not initialized, this execution will be ignored:{},{}", this.tClass, this.getClass());
            return 0;
        }
        Entity where = new Entity(tableName);
        if (keyValue != null) {
            where.set(key, keyValue);
        }
        if (consumer != null) {
            consumer.accept(where);
        }
        Assert.state(where.size() > 0, "????????????????????????:-1");
        return del(where);
    }

    /**
     * ??????????????????
     *
     * @param where ??????
     * @return ????????????
     */
    public int del(Entity where) {
        if (!DbConfig.getInstance().isInit()) {
            // ignore
            log.error("The database is not initialized, this execution will be ignored:{},{}", this.tClass, this.getClass());
            return 0;
        }
        where.setTableName(tableName);
        if (where.isEmpty()) {
            throw new JpomRuntimeException("??????????????????");
        }
        try {
            Db db = Db.use();
            db.setWrapper((Character) null);
            return db.del(where);
        } catch (Exception e) {
            throw warpException(e);
        }
    }

    /**
     * ??????????????????
     *
     * @param data ??????
     * @return true ??????
     */
    public boolean exists(T data) {
        Entity entity = this.dataBeanToEntity(data);
        return this.exists(entity);
    }

    /**
     * ??????????????????
     *
     * @param where ??????
     * @return true ??????
     */
    public boolean exists(Entity where) {
        long count = this.count(where);
        return count > 0;
    }

    /**
     * ??????????????????
     *
     * @param where ??????
     * @return count
     */
    public long count(Entity where) {
        if (!DbConfig.getInstance().isInit()) {
            // ignore
            log.error("The database is not initialized, this execution will be ignored:{},{}", this.tClass, this.getClass());
            return 0;
        }
        where.setTableName(getTableName());
        Db db = Db.use();
        db.setWrapper((Character) null);
        try {
            return db.count(where);
        } catch (Exception e) {
            throw warpException(e);
        }
    }

    /**
     * ??????????????????
     *
     * @param sql sql
     * @return count
     */
    public long count(String sql, Object... params) {
        if (!DbConfig.getInstance().isInit()) {
            // ignore
            log.error("The database is not initialized, this execution will be ignored:{},{}", this.tClass, this.getClass());
            return 0;
        }
        try {
            return Db.use().count(sql, params);
        } catch (Exception e) {
            throw warpException(e);
        }
    }

    /**
     * ????????????
     *
     * @param where ??????
     * @return Entity
     */
    public Entity query(Entity where) {
        List<Entity> entities = this.queryList(where);
        return CollUtil.getFirst(entities);
    }

    /**
     * ?????? list
     *
     * @param where ??????
     * @return data
     */
    public List<T> listByEntity(Entity where) {
        List<Entity> entity = this.queryList(where);
        return this.entityToBeanList(entity);
    }

    /**
     * ????????????
     *
     * @param where ??????
     * @return List
     */
    public List<Entity> queryList(Entity where) {
        if (!DbConfig.getInstance().isInit()) {
            // ignore
            log.error("The database is not initialized, this execution will be ignored:{},{}", this.tClass, this.getClass());
            return null;
        }
        where.setTableName(getTableName());
        Db db = Db.use();
        db.setWrapper((Character) null);
        try {
            return db.find(where);
        } catch (Exception e) {
            throw warpException(e);
        }
    }

    /**
     * ????????????
     *
     * @param wheres ??????
     * @return List
     */
    public List<T> findByCondition(Condition... wheres) {
        if (!DbConfig.getInstance().isInit()) {
            // ignore
            log.error("The database is not initialized, this execution will be ignored:{},{}", this.tClass, this.getClass());
            return null;
        }
        Db db = Db.use();
        db.setWrapper((Character) null);
        try {
            List<Entity> entities = db.findBy(getTableName(), wheres);
            return this.entityToBeanList(entities);
        } catch (Exception e) {
            throw warpException(e);
        }
    }

    /**
     * ????????????
     *
     * @param data   ??????
     * @param count  ????????????
     * @param orders ??????
     * @return List
     */
    public List<T> queryList(T data, int count, Order... orders) {
        Entity where = this.dataBeanToEntity(data);
        Page page = new Page(1, count);
        page.addOrder(orders);
        PageResultDto<T> tPageResultDto = this.listPage(where, page);
        return tPageResultDto.getResult();
    }

    /**
     * ????????????
     *
     * @param where ??????
     * @param page  ??????
     * @return ??????
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public PageResultDto<T> listPage(Entity where, Page page) {
        if (!DbConfig.getInstance().isInit()) {
            // ignore
            log.error("The database is not initialized, this execution will be ignored:{},{}", this.tClass, this.getClass());
            return PageResultDto.EMPTY;
        }
        where.setTableName(getTableName());
        PageResult<Entity> pageResult;
        Db db = Db.use();
        db.setWrapper((Character) null);
        try {
            pageResult = db.page(where, page);
        } catch (Exception e) {
            throw warpException(e);
        }
        //
        List<T> list = pageResult.stream().map(entity -> {
            T entityToBean = this.entityToBean(entity, this.tClass);
            this.fillSelectResult(entityToBean);
            return entityToBean;
        }).collect(Collectors.toList());
        PageResultDto<T> pageResultDto = new PageResultDto(pageResult);
        pageResultDto.setResult(list);
        if (pageResultDto.isEmpty() && pageResultDto.getPage() > 1) {
            Assert.state(pageResultDto.getTotal() <= 0, "????????????????????????,????????????????????????????????????");
        }
        return pageResultDto;
    }

    /**
     * ????????????
     *
     * @param where ??????
     * @param page  ??????
     * @return ??????
     */
    public List<T> listPageOnlyResult(Entity where, Page page) {
        PageResultDto<T> pageResultDto = this.listPage(where, page);
        return pageResultDto.getResult();
    }

    /**
     * sql ??????
     *
     * @param sql    sql ??????
     * @param params ??????
     * @return list
     */
    public List<Entity> query(String sql, Object... params) {
        if (!DbConfig.getInstance().isInit()) {
            // ignore
            log.error("The database is not initialized, this execution will be ignored:{},{}", this.tClass, this.getClass());
            return null;
        }
        try {
            return Db.use().query(sql, params);
        } catch (Exception e) {
            throw warpException(e);
        }
    }

    /**
     * sql ??????
     *
     * @param sql    sql ??????
     * @param params ??????
     * @return list
     */
    public int execute(String sql, Object... params) {
        if (!DbConfig.getInstance().isInit()) {
            // ignore
            log.error("The database is not initialized, this execution will be ignored:{},{}", this.tClass, this.getClass());
            return 0;
        }
        try {
            return Db.use().execute(sql, params);
        } catch (Exception e) {
            throw warpException(e);
        }
    }


    /**
     * sql ?????? list
     *
     * @param sql    sql ??????
     * @param params ??????
     * @return list
     */
    public List<T> queryList(String sql, Object... params) {
        List<Entity> query = this.query(sql, params);
        return this.entityToBeanList(query);
        //		if (query != null) {
        //			return query.stream().map((entity -> {
        //				T entityToBean = this.entityToBean(entity, this.tClass);
        //				this.fillSelectResult(entityToBean);
        //				return entityToBean;
        //			})).collect(Collectors.toList());
        //		}
        //		return null;
    }

    /**
     * ??????????????????
     *
     * @param data ??????
     * @return data
     */
    public List<T> listByBean(T data) {
        Entity where = this.dataBeanToEntity(data);
        List<Entity> entitys = this.queryList(where);
        return this.entityToBeanList(entitys);
    }

    public List<T> entityToBeanList(List<Entity> entitys) {
        if (entitys == null) {
            return null;
        }
        return entitys.stream().map((entity -> {
            T entityToBean = this.entityToBean(entity, this.tClass);
            this.fillSelectResult(entityToBean);
            return entityToBean;
        })).collect(Collectors.toList());
    }

    /**
     * ??????????????????
     *
     * @param data ??????
     * @return data
     */
    public T queryByBean(T data) {
        Entity where = this.dataBeanToEntity(data);
        Entity entity = this.query(where);
        T entityToBean = this.entityToBean(entity, this.tClass);
        this.fillSelectResult(entityToBean);
        return entityToBean;
    }

    /**
     * ???????????? ??????
     *
     * @param data ??????
     */
    protected void fillSelectResult(T data) {
    }

    /**
     * ????????????
     *
     * @param e ??????
     */
    protected JpomRuntimeException warpException(Exception e) {
        String message = e.getMessage();
        if (e instanceof MVStoreException || ExceptionUtil.isCausedBy(e, MVStoreException.class)) {
            if (StrUtil.containsIgnoreCase(message, "The write format 1 is smaller than the supported format 2")) {
                log.warn(message);
                String tip = "????????????????????????" + StrUtil.LF;
                tip += StrUtil.TAB + "1. ????????????????????? ????????????????????????????????? --backup-h2???" + StrUtil.LF;
                tip += StrUtil.TAB + "2. ???????????????????????????( sql ??????) ?????????????????????????????????????????????????????? --replace-import-h2-sql=/xxxx.sql (???????????????????????????????????????????????? sql ??????????????????)???";
                return new JpomRuntimeException("????????????????????????,??????????????????????????????" + StrUtil.LF + tip + StrUtil.LF, -1);
            }
        }
        if (e instanceof JdbcSQLNonTransientException || ExceptionUtil.isCausedBy(e, JdbcSQLNonTransientException.class)) {
            return new JpomRuntimeException("???????????????,?????????????????????????????????(????????????????????????),??????????????????????????????????????????????????????????????? --recover:h2db ???????????????,???" + message, e);
        }
        if (e instanceof JdbcSQLNonTransientConnectionException) {
            return new JpomRuntimeException("???????????????,????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????" + message, e);
        }
        return new JpomRuntimeException("???????????????", e);
    }
}
