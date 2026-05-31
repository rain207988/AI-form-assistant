use bite_excel;
drop table if exists `users`;
CREATE TABLE users
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID，主键，自增长，从10000001开始',
    username      VARCHAR(50)  NOT NULL UNIQUE COMMENT '用户名，唯一标识，用于登录和显示',
    email         VARCHAR(100) UNIQUE COMMENT '邮箱地址，唯一，用于登录和通知',
    password_hash VARCHAR(255) COMMENT '密码哈希值，使用BCrypt加密存储',
    INDEX idx_email (email),
    INDEX idx_username (username)
) COMMENT '用户表' CHARSET = utf8mb4  AUTO_INCREMENT = 10000001;


--文件服务相关的sql
-- 创建文件表
drop table if exists `files`;
CREATE TABLE files
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '文件唯一标识，主键，自增长，从10000001开始',
    user_id       BIGINT       NOT NULL COMMENT '用户ID，关联用户表的id，表示文件所属用户',
    file_name     VARCHAR(255) NOT NULL COMMENT '原始文件名，包含文件扩展名',
    file_path     VARCHAR(500) COMMENT '文件存储路径，相对路径或绝对路径',
    file_size     BIGINT COMMENT '文件大小，单位：字节',
    oss_key       VARCHAR(300) COMMENT '对象存储服务中的文件唯一标识key',
    upload_status TINYINT   DEFAULT 1 COMMENT '上传状态：1-上传成功，0-上传失败，2-上传中，3-处理中，4-转换完成',
    INDEX idx_user_id (user_id) COMMENT '用户ID索引，加速按用户查询',
    INDEX idx_upload_status (upload_status) COMMENT '上传状态索引'
) COMMENT '文件表，用于存储用户上传的文件信息' CHARSET = utf8mb4 AUTO_INCREMENT = 10000001;

-- 创建文件与表名映射关系表（支持单文件多sheet场景）
drop table if exists `file_table_mappings`;
CREATE TABLE file_table_mappings
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    file_id     BIGINT       NOT NULL COMMENT '文件ID，关联files.id',
    table_name  VARCHAR(100) NOT NULL COMMENT 'MySQL表名',
    sheet_index INT          NOT NULL DEFAULT 0 COMMENT 'sheet顺序索引，从0开始',
    sheet_name  VARCHAR(100)          DEFAULT NULL COMMENT '原始sheet名称',
    UNIQUE KEY uk_file_table (file_id, table_name),
    INDEX idx_file_id (file_id),
    INDEX idx_sheet_index (sheet_index)
) COMMENT '文件与MySQL表关系映射（多sheet）' CHARSET = utf8mb4 AUTO_INCREMENT = 10000001;

-- 创建字段映射关系表
drop table if exists `field_mappings`;
CREATE TABLE field_mappings
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '映射关系ID，主键，自增长',
    file_id         BIGINT       NOT NULL COMMENT '文件ID，关联files表的id',
    table_name      VARCHAR(100) NOT NULL COMMENT 'MySQL表名',
    db_field_name   VARCHAR(100) NOT NULL COMMENT '数据库字段名',
    original_header VARCHAR(255) NOT NULL COMMENT '原始Excel表头',
    field_order     INT          NOT NULL DEFAULT 0 COMMENT '字段在Excel中的顺序',
    UNIQUE KEY uk_file_db_field (file_id, db_field_name),
    INDEX idx_file_id (file_id),
    INDEX idx_table_name (table_name)
) COMMENT 'Excel表头与MySQL字段映射关系表' CHARSET = utf8mb4 AUTO_INCREMENT = 10000001;

-- 创建AI服务请求记录表
drop table if exists `ai_requests`;
CREATE TABLE ai_requests
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID，自增长，从10000001开始',
    user_id      BIGINT   NOT NULL COMMENT '用户ID，关联用户表',
    file_id      BIGINT NOT NULL COMMENT '文件ID，关联文件表',
    user_input   TEXT     NOT NULL COMMENT '用户输入的请求内容',
    ai_response  TEXT COMMENT 'AI返回的响应内容',
    status       TINYINT  DEFAULT 0 COMMENT '请求状态：0-处理中、1-成功、2-失败、3-部分成功',
    INDEX idx_user_id (user_id) COMMENT '用户id索引'
) COMMENT 'AI服务请求记录表' CHARSET = utf8mb4 AUTO_INCREMENT = 10000001;