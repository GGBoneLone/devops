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
package io.jpom.controller;

import cn.hutool.cache.CacheUtil;
import cn.hutool.cache.impl.LFUCache;
import cn.hutool.cache.impl.TimedCache;
import cn.hutool.captcha.CircleCaptcha;
import cn.hutool.core.date.BetweenFormatter;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.servlet.ServletUtil;
import cn.hutool.jwt.JWT;
import cn.jiangzeyin.common.JsonMessage;
import cn.jiangzeyin.common.validator.ValidatorConfig;
import cn.jiangzeyin.common.validator.ValidatorItem;
import cn.jiangzeyin.common.validator.ValidatorRule;
import com.alibaba.fastjson.JSONObject;
import io.jpom.common.BaseServerController;
import io.jpom.common.ServerOpenApi;
import io.jpom.common.interceptor.LoginInterceptor;
import io.jpom.common.interceptor.NotLogin;
import io.jpom.model.user.UserModel;
import io.jpom.model.data.WorkspaceModel;
import io.jpom.model.dto.UserLoginDto;
import io.jpom.permission.ClassFeature;
import io.jpom.permission.Feature;
import io.jpom.permission.MethodFeature;
import io.jpom.service.user.UserBindWorkspaceService;
import io.jpom.service.user.UserService;
import io.jpom.system.ServerConfigBean;
import io.jpom.system.ServerExtConfigBean;
import io.jpom.util.JwtUtil;
import io.jpom.util.StringUtil;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.awt.*;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ????????????
 *
 * @author Administrator
 */
@RestController
@Feature(cls = ClassFeature.USER)
public class LoginControl extends BaseServerController {
    /**
     * ip ?????????
     */
    public static final LFUCache<String, Integer> LFU_CACHE = new LFUCache<>(1000);
    /**
     * ????????????????????????
     */
    private static final TimedCache<String, String> MFA_TOKEN = CacheUtil.newTimedCache(TimeUnit.MINUTES.toMillis(10));

    private static final String LOGIN_CODE = "login_code";

    private final UserService userService;
    private final UserBindWorkspaceService userBindWorkspaceService;

    public LoginControl(UserService userService,
                        UserBindWorkspaceService userBindWorkspaceService) {
        this.userService = userService;
        this.userBindWorkspaceService = userBindWorkspaceService;
    }

    /**
     * ?????????
     *
     * @throws IOException IO
     */
    @RequestMapping(value = "randCode.png", method = RequestMethod.GET, produces = MediaType.IMAGE_PNG_VALUE)
    @NotLogin
    public void randCode() throws IOException {
        if (ServerExtConfigBean.getInstance().getDisabledCaptcha()) {
            ServletUtil.write(getResponse(), JsonMessage.getString(200, "??????????????????"), MediaType.APPLICATION_JSON_VALUE);
            return;
        }
        int height = 50;
        CircleCaptcha circleCaptcha = new CircleCaptcha(100, height, 4, 8);
        // ?????????????????????
        circleCaptcha.setFont(new Font(null, Font.PLAIN, (int) (height * 0.75)));
        circleCaptcha.createCode();
        HttpServletResponse response = getResponse();
        circleCaptcha.write(response.getOutputStream());
        String code = circleCaptcha.getCode();
        setSessionAttribute(LOGIN_CODE, code);
    }

    /**
     * ?????? ip ????????????
     */
    private void ipError() {
        if (ServerExtConfigBean.getInstance().getIpErrorLockTime() <= 0) {
            return;
        }
        String ip = getIp();
        int count = ObjectUtil.defaultIfNull(LFU_CACHE.get(ip), 0) + 1;
        LFU_CACHE.put(ip, count, ServerExtConfigBean.getInstance().getIpErrorLockTime());
    }

    private void ipSuccess() {
        String ip = getIp();
        LFU_CACHE.remove(ip);
    }

    /**
     * ????????????ip ???????????????????????????10?????????????????????ip
     *
     * @return true
     */
    private boolean ipLock() {
        if (ServerExtConfigBean.getInstance().userAlwaysLoginError <= 0) {
            return false;
        }
        String ip = getIp();
        Integer count = LFU_CACHE.get(ip);
        if (count == null) {
            count = 0;
        }
        // ??????10?????? ???ip
        return count > ServerExtConfigBean.getInstance().userAlwaysLoginError * 10;
    }

    /**
     * ????????????
     *
     * @param userName ?????????
     * @param userPwd  ????????????
     * @param code     ?????????
     * @return json
     */
    @PostMapping(value = "userLogin", produces = MediaType.APPLICATION_JSON_VALUE)
    @NotLogin
    @Feature(method = MethodFeature.EXECUTE, resultCode = {200, 201}, logResponse = false)
    public String userLogin(
        @ValidatorConfig(value = {
            @ValidatorItem(value = ValidatorRule.NOT_EMPTY, msg = "?????????????????????")
        }) String userName,
        @ValidatorConfig(value = {
            @ValidatorItem(value = ValidatorRule.NOT_EMPTY, msg = "?????????????????????")
        }) String userPwd,
        String code) {
        if (this.ipLock()) {
            return JsonMessage.getString(400, "????????????????????????????????????");
        }
        synchronized (userName.intern()) {
            UserModel userModel = userService.getByKey(userName);
            if (userModel == null) {
                this.ipError();
                return JsonMessage.getString(400, "????????????????????????????????????????????????,???????????????????????????");
            }
            if (!ServerExtConfigBean.getInstance().getDisabledCaptcha()) {
                // ???????????????
                String sCode = getSessionAttribute(LOGIN_CODE);
                Assert.state(StrUtil.equalsIgnoreCase(code, sCode), "???????????????????????????");
                removeSessionAttribute(LOGIN_CODE);
            }
            UserModel updateModel = null;
            try {
                long lockTime = userModel.overLockTime();
                if (lockTime > 0) {
                    String msg = DateUtil.formatBetween(lockTime * 1000, BetweenFormatter.Level.SECOND);
                    updateModel = userModel.errorLock();
                    this.ipError();
                    return JsonMessage.getString(400, "????????????????????????????????????????????????" + msg + ",?????????????????????");
                }
                // ??????
                if (userService.simpleLogin(userName, userPwd) != null) {
                    updateModel = UserModel.unLock(userName);
                    this.ipSuccess();
                    // ?????????????????? ????????????
                    boolean bindMfa = userService.hasBindMfa(userName);
                    if (bindMfa) {
                        //
                        JSONObject jsonObject = new JSONObject();
                        String uuid = IdUtil.fastSimpleUUID();
                        MFA_TOKEN.put(uuid, userName);
                        jsonObject.put("tempToken", uuid);
                        return JsonMessage.getString(201, "????????????????????????", jsonObject);
                    }
                    UserLoginDto userLoginDto = this.createToken(userModel);
                    return JsonMessage.getString(200, "????????????", userLoginDto);
                } else {
                    updateModel = userModel.errorLock();
                    this.ipError();
                    return JsonMessage.getString(501, "????????????????????????????????????????????????,???????????????????????????");
                }
            } finally {
                if (updateModel != null) {
                    userService.update(updateModel);
                }
                // ????????????????????????
                BaseServerController.resetInfo(userModel);
            }
        }
    }

    private UserLoginDto createToken(UserModel userModel) {
        // ??????????????????
        List<WorkspaceModel> bindWorkspaceModels = userBindWorkspaceService.listUserWorkspaceInfo(userModel);
        Assert.notEmpty(bindWorkspaceModels, "?????????????????????????????????????????????????????????????????????");
        UserLoginDto userLoginDto = userService.getUserJwtId(userModel);
        //					UserLoginDto userLoginDto = new UserLoginDto(JwtUtil.builder(userModel, jwtId), jwtId);
        userLoginDto.setBindWorkspaceModels(bindWorkspaceModels);
        //
        setSessionAttribute(LoginInterceptor.SESSION_NAME, userModel);
        return userLoginDto;
    }

    @GetMapping(value = "mfa_verify", produces = MediaType.APPLICATION_JSON_VALUE)
    @NotLogin
    public String mfaVerify(String token, String code) {
        String userId = MFA_TOKEN.get(token);
        if (StrUtil.isEmpty(userId)) {
            return JsonMessage.getString(201, "???????????????????????????????????????");
        }
        boolean mfaCode = userService.verifyMfaCode(userId, code);
        Assert.state(mfaCode, "??????????????????,???????????????");
        UserModel userModel = userService.getByKey(userId);
        //
        UserLoginDto userLoginDto = this.createToken(userModel);
        MFA_TOKEN.remove(token);
        return JsonMessage.getString(200, "????????????", userLoginDto);
    }

    /**
     * ????????????
     *
     * @return json
     */
    @RequestMapping(value = "logout2", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    @NotLogin
    public String logout() {
        getSession().invalidate();
        return JsonMessage.getString(200, "????????????");
    }

    /**
     * ??????token
     *
     * @return json
     */
    @RequestMapping(value = "renewal", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @NotLogin
    public String renewalToken() {
        String token = getRequest().getHeader(ServerOpenApi.HTTP_HEAD_AUTHORIZATION);
        if (StrUtil.isEmpty(token)) {
            return JsonMessage.getString(ServerConfigBean.AUTHORIZE_TIME_OUT_CODE, "??????token??????");
        }
        JWT jwt = JwtUtil.readBody(token);
        if (JwtUtil.expired(jwt, 0)) {
            int renewal = ServerExtConfigBean.getInstance().getAuthorizeRenewal();
            if (jwt == null || renewal <= 0 || JwtUtil.expired(jwt, TimeUnit.MINUTES.toSeconds(renewal))) {
                return JsonMessage.getString(ServerConfigBean.AUTHORIZE_TIME_OUT_CODE, "??????token??????");
            }
        }
        UserModel userModel = userService.checkUser(JwtUtil.getId(jwt));
        if (userModel == null) {
            return JsonMessage.getString(ServerConfigBean.AUTHORIZE_TIME_OUT_CODE, "?????????????????????");
        }
        UserLoginDto userLoginDto = userService.getUserJwtId(userModel);
        return JsonMessage.getString(200, "", userLoginDto);
    }

    /**
     * ?????? demo ???????????????
     */
    @GetMapping(value = "user_demo_info", produces = MediaType.APPLICATION_JSON_VALUE)
    @NotLogin
    public String demoInfo() {
        String userDemoTip = ServerExtConfigBean.getInstance().getUserDemoTip();
        userDemoTip = StringUtil.convertFileStr(userDemoTip, StrUtil.EMPTY);

        if (StrUtil.isEmpty(userDemoTip) || !userService.hasDemoUser()) {
            return JsonMessage.getString(200, "");
        }
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("user", UserModel.DEMO_USER);
        return JsonMessage.getString(200, userDemoTip, jsonObject);
    }
}
