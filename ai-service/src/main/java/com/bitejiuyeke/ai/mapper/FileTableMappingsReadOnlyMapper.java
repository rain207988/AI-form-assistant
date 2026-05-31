package com.bitejiuyeke.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bitejiuyeke.file.entity.FileTableMappingEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 只读FileTableMappings表
 */
@Mapper
public interface FileTableMappingsReadOnlyMapper extends BaseMapper<FileTableMappingEntity> {
}
