package cn.keepbx.jpom.system.init;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.jiangzeyin.common.PreLoadClass;
import cn.jiangzeyin.common.PreLoadMethod;
import cn.keepbx.jpom.model.system.JpomManifest;
import cn.keepbx.jpom.system.AgentAuthorize;
import cn.keepbx.jpom.system.AgentConfigBean;
import cn.keepbx.jpom.system.ConfigBean;
import cn.keepbx.jpom.system.ExtConfigBean;

import java.io.File;

/**
 * 检查授权信息
 *
 * @author jiangzeyin
 * @date 2019/4/17
 */
@PreLoadClass
public class CheckAuthorize {

    @PreLoadMethod
    private static void startAutoBackLog() {
        AgentAuthorize.getInstance();
    }

    /**
     * 修护脚本模板路径
     */
    @PreLoadMethod
    private static void repairScriptPath() {
        if (!JpomManifest.getInstance().isDebug()) {
            if (StrUtil.compareVersion(JpomManifest.getInstance().getVersion(), "2.4.2") < 0) {
                return;
            }
        }
        File oldDir = FileUtil.file(ExtConfigBean.getInstance().getPath(), AgentConfigBean.SCRIPT_DIRECTORY);
        if (!oldDir.exists()) {
            return;
        }
        File newDir = FileUtil.file(ConfigBean.getInstance().getDataPath(), AgentConfigBean.SCRIPT_DIRECTORY);
        FileUtil.move(oldDir, newDir, true);
    }
}
