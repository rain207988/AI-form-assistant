package com.bitejiuyeke.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * AI服务的启动入口
 */
@SpringBootApplication
@ComponentScan(basePackages = {
        "com.bitejiuyeke.ai",
        "com.bitejiuyeke.common"
})
@Slf4j
public class AiApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
        log.info("Port: 9002");
    }
}
