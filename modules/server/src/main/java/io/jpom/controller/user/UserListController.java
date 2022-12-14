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
package io.jpom.controller.user;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.jiangzeyin.common.JsonMessage;
import cn.jiangzeyin.common.validator.ValidatorItem;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.jpom.common.BaseServerController;
import io.jpom.common.Const;
import io.jpom.model.PageResultDto;
import io.jpom.model.user.UserModel;
import io.jpom.permission.ClassFeature;
import io.jpom.permission.Feature;
import io.jpom.permission.MethodFeature;
import io.jpom.permission.SystemPermission;
import io.jpom.service.user.UserBindWorkspaceService;
import io.jpom.service.user.UserService;
import io.jpom.system.ServerExtConfigBean;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ????????????
 *
 * @author Administrator
 */
@RestController
@RequestMapping(value = "/user")
@Feature(cls = ClassFeature.USER)
@SystemPermission
public class UserListController extends BaseServerController {

    private final UserService userService;
    private final UserBindWorkspaceService userBindWorkspaceService;

    public UserListController(UserService userService,
                              UserBindWorkspaceService userBindWorkspaceService) {
        this.userService = userService;
        this.userBindWorkspaceService = userBindWorkspaceService;
    }

    /**
     * ??????????????????
     *
     * @return json
     */
    @RequestMapping(value = "get_user_list", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.LIST)
    public JsonMessage<PageResultDto<UserModel>> getUserList() {
        PageResultDto<UserModel> userModelPageResultDto = userService.listPage(getRequest());
        userModelPageResultDto.each(userModel -> {
            boolean bindMfa = userService.hasBindMfa(userModel.getId());
            if (bindMfa) {
                userModel.setTwoFactorAuthKey("true");
            }
        });
        return new JsonMessage<>(200, "", userModelPageResultDto);
    }

    /**
     * ???????????????????????????
     * get all admin user list
     *
     * @return json
     * @author Hotstrip
     */
    @RequestMapping(value = "get_user_list_all", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.LIST)
    public String getUserListAll() {
        List<UserModel> list = userService.list();
        return JsonMessage.getString(200, "success", list);
    }

    /**
     * ????????????
     *
     * @param type ????????????
     * @return String
     */
    @PostMapping(value = "edit", produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.EDIT)
    public JsonMessage<JSONObject> addUser(String type) {
        //
        boolean create = StrUtil.equals(type, "add");
        UserModel userModel = this.parseUser(create);
        JSONObject result = new JSONObject();
        if (create) {
            String randomPwd = RandomUtil.randomString(UserModel.SALT_LEN);
            String sha1Pwd = SecureUtil.sha1(randomPwd);
            userModel.setSalt(userService.generateSalt());
            userModel.setPassword(SecureUtil.sha1(sha1Pwd + userModel.getSalt()));
            userService.insert(userModel);
            result.put("randomPwd", randomPwd);
        } else {
            UserModel model = userService.getByKey(userModel.getId());
            Assert.notNull(model, "????????????????????????");
            boolean systemUser = userModel.isSystemUser();
            if (!systemUser) {
                Assert.state(!model.isSuperSystemUser(), "????????????????????????????????????");
            }
            if (model.isSuperSystemUser()) {
                Assert.state(userModel.getStatus() == 1, "???????????????????????????");
            }
            UserModel optUser = getUser();
            if (StrUtil.equals(model.getId(), optUser.getId())) {
                Assert.state(optUser.isSuperSystemUser(), "???????????????????????????");
            }
            userService.update(userModel);
            // ???????????????
            userBindWorkspaceService.deleteByUserId(userModel.getId());
        }
        return new JsonMessage<>(200, "????????????", result);
    }

    private UserModel parseUser(boolean create) {
        String id = getParameter("id");
        boolean email = Validator.isEmail(id);
        if (email) {
            int length = id.length();
            Assert.state(length <= Const.ID_MAX_LEN && length >= UserModel.USER_NAME_MIN_LEN, "??????????????????????????????,????????????" + UserModel.USER_NAME_MIN_LEN + "-" + Const.ID_MAX_LEN);
        } else {
            Validator.validateGeneral(id, UserModel.USER_NAME_MIN_LEN, Const.ID_MAX_LEN, "?????????????????????,??????????????????" + UserModel.USER_NAME_MIN_LEN + "-" + Const.ID_MAX_LEN);
        }

        Assert.state(!StrUtil.equalsAnyIgnoreCase(id, UserModel.SYSTEM_OCCUPY_NAME, UserModel.SYSTEM_ADMIN), "????????????????????????????????????");

        UserModel userModel = new UserModel();
        UserModel optUser = getUser();
        if (create) {
            long size = userService.count();
            Assert.state(size <= ServerExtConfigBean.getInstance().userMaxCount, "????????????????????????????????????");
            // ???????????????
            boolean exists = userService.exists(new UserModel(id));
            Assert.state(!exists, "?????????????????????");
            userModel.setParent(optUser.getId());
        }
        userModel.setId(id);
        //
        String name = getParameter("name");
        Assert.hasText(name, "?????????????????????");
        int len = name.length();
        Assert.state(len <= 10 && len >= 2, "?????????????????????2-10");

        userModel.setName(name);

//        String password = getParameter("password");
//        if (create || StrUtil.isNotEmpty(password)) {
//            Assert.hasText(password, "??????????????????");
//            // ????????????
//            Assert.state(create || optUser.isSystemUser(), "?????????????????????????????????????????????");
//            userModel.setSalt(userService.generateSalt());
//            userModel.setPassword(SecureUtil.sha1(password + userModel.getSalt()));
//        }

        int systemUser = getParameterInt("systemUser", 0);
        userModel.setSystemUser(systemUser);
        //
        String permissionGroup = getParameter("permissionGroup");
        List<String> permissionGroupList = StrUtil.split(permissionGroup, StrUtil.AT);
        Assert.notEmpty(permissionGroupList, "????????????????????????");
        userModel.setPermissionGroup(CollUtil.join(permissionGroupList, StrUtil.AT, StrUtil.AT, StrUtil.AT));
        //
        int status = getParameterInt("status", 1);
        Assert.state(status == 0 || status == 1, "???????????????????????????");
        userModel.setStatus(status);
        return userModel;
    }

    /**
     * ????????????
     *
     * @param id ??????id
     * @return String
     */
    @RequestMapping(value = "deleteUser", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.DEL)
    public String deleteUser(String id) {
        UserModel userName = getUser();
        Assert.state(!StrUtil.equals(userName.getId(), id), "??????????????????");

        UserModel userModel = userService.getByKey(id);
        Assert.notNull(userModel, "????????????");
        if (userModel.isSystemUser()) {
            // ???????????????????????????????????????
            Assert.state(userService.systemUserCount() > 1, "???????????????????????????????????????????????????????????????");
        }
        // ?????????????????????????????????????????????
        Assert.state(!userModel.isRealDemoUser(), "???????????????????????????");
        userService.delByKey(id);
        // ??????????????????
        userBindWorkspaceService.deleteByUserId(id);
        return JsonMessage.getString(200, "????????????");
    }

    /**
     * ????????????????????????
     *
     * @param id id
     * @return json
     */
    @GetMapping(value = "unlock", produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.EDIT)
    public String unlock(@ValidatorItem String id) {
        UserModel update = UserModel.unLock(id);
        userService.update(update);
        return JsonMessage.getString(200, "????????????");
    }

    /**
     * ???????????? mfa
     *
     * @param id id
     * @return json
     */
    @GetMapping(value = "close_user_mfa", produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.EDIT)
    @SystemPermission(superUser = true)
    public String closeMfa(@ValidatorItem String id) {
        UserModel update = new UserModel(id);
        update.setTwoFactorAuthKey(StrUtil.EMPTY);
        userService.update(update);
        return JsonMessage.getString(200, "????????????");
    }

    /**
     * ??????????????????
     *
     * @param id id
     * @return json
     */
    @GetMapping(value = "rest-user-pwd", produces = MediaType.APPLICATION_JSON_VALUE)
    @Feature(method = MethodFeature.EDIT)
    public String restUserPwd(@ValidatorItem String id) {
        UserModel userModel = userService.getByKey(id);
        Assert.notNull(userModel, "???????????????");
        Assert.state(!userModel.isSuperSystemUser(), "????????????????????????????????????????????????");
        //???????????????????????????
        Assert.state(!userModel.isRealDemoUser(), "?????????????????????????????????");
        String randomPwd = RandomUtil.randomString(UserModel.SALT_LEN);
        String sha1Pwd = SecureUtil.sha1(randomPwd);
        userService.updatePwd(id, sha1Pwd);
        //
        JSONObject result = new JSONObject();
        result.put("randomPwd", randomPwd);
        return JsonMessage.getString(200, "????????????", result);
    }
}
