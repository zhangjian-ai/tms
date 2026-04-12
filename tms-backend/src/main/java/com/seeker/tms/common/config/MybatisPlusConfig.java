package com.seeker.tms.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(){
        // 1. 创建 MybatisPlusInterceptor 实例
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 2. 创建分页插件实例
        PaginationInnerInterceptor paginationInnerInterceptor = new PaginationInnerInterceptor();
        paginationInnerInterceptor.setDbType(DbType.MYSQL); // 设置数据库类型
        paginationInnerInterceptor.setMaxLimit(1000L); // 设置最大分页限制

        // 3. 将分页插件添加到 mp拦截器，然后返回
        interceptor.addInnerInterceptor(paginationInnerInterceptor);
        return interceptor;
    }
}
