package com.bitejiuyeke.user;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * 用户服务启动类
 */
@SpringBootApplication
@MapperScan("com.bitejiuyeke.user.mapper")
@ComponentScan(basePackages = {
        "com.bitejiuyeke.user",
        "com.bitejiuyeke.common"
})
@Slf4j
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
        log.info("Port: 9001");
    }
}
