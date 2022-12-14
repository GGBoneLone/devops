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
package io.jpom.common;

/**
 * @author bwcx_jzy
 * @since 2022/8/30
 */
public class ServerConst extends Const {

    /**
     * String const
     */
    public static final String ID_STR = "id";

    public static final String GROUP_STR = "group";

    /**
     * 工作空间全局
     */
    public static final String WORKSPACE_GLOBAL = "GLOBAL";

    /**
     * SQL backup default directory name
     * 数据库备份默认目录名称
     */
    public static final String BACKUP_DIRECTORY_NAME = "backup";
    /**
     * h2 数据库表名字段
     */
    public static final String TABLE_NAME = "TABLE_NAME";
    /**
     * 备份 SQL 文件 后缀
     */
    public static final String SQL_FILE_SUFFIX = ".sql";

    /**
     * String get
     */
    public static final String GET_STR = "get";

    /**
     * id_rsa
     */
    public static final String ID_RSA = "_id_rsa";
    /**
     * sshkey
     */
    public static final String SSH_KEY = "sshkey";
    /**
     * 引用工作空间环境变量的前缀
     */
    public static final String REF_WORKSPACE_ENV = "$ref.wEnv.";
}
