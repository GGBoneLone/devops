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
package io.jpom.service.user;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestAlgorithm;
import cn.hutool.crypto.digest.Digester;
import cn.jiangzeyin.common.spring.SpringUtil;
import io.jpom.model.BaseIdModel;
import io.jpom.model.user.UserModel;
import io.jpom.model.user.TriggerTokenLogBean;
import io.jpom.service.IStatusRecover;
import io.jpom.service.ITriggerToken;
import io.jpom.service.h2db.BaseDbService;
import io.jpom.service.system.SystemParametersServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @author bwcx_jzy
 * @since 2022/7/22
 */
@Service
@Slf4j
public class TriggerTokenLogServer extends BaseDbService<TriggerTokenLogBean> implements IStatusRecover {

    /**
     * ???????????????
     */
    private static final int BUILD_INFO_TRIGGER_TOKEN_FILL_LEN = 3;

    private final SystemParametersServer parametersServer;
    private final UserService userService;

    public TriggerTokenLogServer(SystemParametersServer parametersServer,
                                 UserService userService) {
        this.parametersServer = parametersServer;
        this.userService = userService;
    }

    /**
     * ?????? token ????????????ID
     *
     * @param token token
     * @param type  ????????????
     * @return user
     */
    public UserModel getUserByToken(String token, String type) {
        TriggerTokenLogBean tokenLogBean = super.getByKey(token);
        if (tokenLogBean != null) {
            UserModel userModel = userService.getByKey(tokenLogBean.getUserId());
            if (userModel != null && StrUtil.equals(type, tokenLogBean.getType())) {
                return userModel;
            }
        }
        // ?????????????????????
        TriggerTokenLogBean where = new TriggerTokenLogBean();
        where.setTriggerToken(token);
        where.setType(type);
        List<TriggerTokenLogBean> triggerTokenLogBeans = this.listByBean(where);
        return Optional.ofNullable(triggerTokenLogBeans)
            .map(CollUtil::getFirst)
            .map(triggerTokenLogBean -> userService.getByKey(triggerTokenLogBean.getUserId()))
            .orElse(null);
    }

    /**
     * ???????????? token
     *
     * @param oldToken ???????????? token
     * @param type     ??????
     * @param dataId   ??????ID
     * @param userId   ??????ID
     * @return ??? token
     */
    public String restToken(String oldToken, String type, String dataId, String userId) {
        if (StrUtil.isNotEmpty(oldToken)) {
            TriggerTokenLogBean tokenLogBean = this.getByKey(oldToken);
            if (tokenLogBean != null) {
                this.delByKey(oldToken);
            } else {
                // ??????????????????????????????
                TriggerTokenLogBean where = new TriggerTokenLogBean();
                where.setTriggerToken(oldToken);
                where.setType(type);
                List<TriggerTokenLogBean> triggerTokenLogBeans = this.listByBean(where);
                Optional.ofNullable(triggerTokenLogBeans)
                    .ifPresent(triggerTokenLogBeans1 ->
                        triggerTokenLogBeans1.forEach(triggerTokenLogBean -> this.delByKey(triggerTokenLogBean.getId()))
                    );
            }
        }
        // ?????? token
        return this.createToken(type, dataId, userId);
    }

    /**
     * ????????? token
     *
     * @param type   ??????
     * @param dataId ??????ID
     * @param userId ??????ID
     * @return token
     */
    public String createToken(String type, String dataId, String userId) {
        TriggerTokenLogBean trigger = new TriggerTokenLogBean();
        String uuid = IdUtil.fastSimpleUUID();
        trigger.setId(uuid);
        trigger.setTriggerToken(uuid);
        trigger.setType(type);
        trigger.setDataId(dataId);
        trigger.setUserId(userId);
        this.insert(trigger);
        return uuid;
    }

    @Override
    public int statusRecover() {
        String name = "sync_trigger_token";
        String triggerToken = parametersServer.getConfig(name, String.class);
        if (StrUtil.isNotEmpty(triggerToken)) {
            // ??????????????????
            return 0;
        }
        List<UserModel> list = userService.list();
        if (CollUtil.isEmpty(list)) {
            log.warn("TriggerToken status recover,user list empty");
            return -1;
        }
        List<String> userIds = list.stream().map(BaseIdModel::getId).collect(Collectors.toList());
        Map<String, ITriggerToken> beansOfType = SpringUtil.getApplicationContext().getBeansOfType(ITriggerToken.class);

        int count = beansOfType.values().stream().mapToInt(value -> {
            Map<String, String> tokens = value.allTokens();
            List<TriggerTokenLogBean> triggerTokenLogBeans = tokens.entrySet().stream().map(entry -> {
                String userId = parseUserId(userIds, entry.getValue());
                if (userId == null) {
                    return null;
                }
                TriggerTokenLogBean triggerTokenLogBean = new TriggerTokenLogBean();
                triggerTokenLogBean.setId(IdUtil.fastSimpleUUID());
                triggerTokenLogBean.setTriggerToken(entry.getValue());
                triggerTokenLogBean.setDataId(entry.getKey());
                triggerTokenLogBean.setType(value.typeName());
                triggerTokenLogBean.setUserId(userId);
                return triggerTokenLogBean;
            }).filter(Objects::nonNull).collect(Collectors.toList());
            // ??????
            this.insert(triggerTokenLogBeans);
            //
            return CollUtil.size(triggerTokenLogBeans);
        }).sum();
        parametersServer.upsert(name, count, name);
        log.info("trigger token sync count:{}", count);
        return count;
    }

    /**
     * ?????? ??????????????? token ????????????ID
     *
     * @param userIds ??????????????? ID
     * @param token   token
     * @return userId
     */
    private String parseUserId(List<String> userIds, String token) {
        String digestCountStr = StrUtil.sub(token, 0, TriggerTokenLogServer.BUILD_INFO_TRIGGER_TOKEN_FILL_LEN);
        String result = StrUtil.subSuf(token, TriggerTokenLogServer.BUILD_INFO_TRIGGER_TOKEN_FILL_LEN);
        int digestCount = Convert.toInt(digestCountStr, 1);
        for (String userId : userIds) {
            String nowStr = new Digester(DigestAlgorithm.SHA256).setDigestCount(digestCount).digestHex(userId);
            if (StrUtil.equals(nowStr, result)) {
                return userId;
            }
        }
        return null;
    }
}
