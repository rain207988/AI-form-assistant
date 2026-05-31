package com.bitejiuyeke.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bitejiuyeke.file.entity.FilesEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 只读files表
 */
@Mapper
public interface FilesReadOnlyMapper extends BaseMapper<FilesEntity> {
}
