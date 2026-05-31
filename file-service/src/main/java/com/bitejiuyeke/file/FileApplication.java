package com.bitejiuyeke.file;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * 文件服务启动类
 */
@Slf4j
@SpringBootApplication
@MapperScan(basePackages = {"com.bitejiuyeke.file.mapper"})
@ComponentScan(basePackages = {
        "com.bitejiuyeke.common",
        "com.bitejiuyeke.file"
})
public class FileApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileApplication.class, args);
        log.info("Port: 9003");
    }

}
