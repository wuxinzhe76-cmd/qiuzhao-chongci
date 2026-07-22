package com.charles.interview.arena.model.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("programming_language")
public class ProgrammingLanguage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 语言名称(Java/Python3)
     */
    private String languageName;

    /**
     * 语言代码(java/python3)
     */
    private String languageCode;

    /**
     * 版本号
     */
    private String version;

    /**
     * 编译命令(解释型语言为空)
     */
    private String compileCommand;

    /**
     * 运行命令
     */
    private String runCommand;

    /**
     * 图标URL
     */
    private String icon;

    /**
     * 是否启用:0禁用 1启用
     */
    private Integer isActive;

    /**
     * 创建用户ID
     */
    private Long userId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
