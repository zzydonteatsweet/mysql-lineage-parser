package com.zzy.mysqllineageparser;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.zzy.mysqllineageparser.mybatis.mapper")
public class MysqlLineageParserApplication {

    public static void main(String[] args) {
        SpringApplication.run(MysqlLineageParserApplication.class, args);
    }

}
