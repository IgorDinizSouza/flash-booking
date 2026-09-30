package com.flashbooking.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** Registra as interfaces de com.flashbooking.repository como mappers MyBatis (SQL em resources/mapper/*.xml). */
@Configuration
@MapperScan("com.flashbooking.repository")
public class MyBatisConfig {
}
