package com.seeker.tms.common.config;

import com.github.xiaoymin.knife4j.spring.annotations.EnableKnife4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.Contact;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2WebMvc;

/**
 * Knife4j (Swagger) 配置
 */
@Configuration
@EnableSwagger2WebMvc
@EnableKnife4j
public class Knife4jConfig {

    /**
     * 全局 API 分组
     */
    @Bean
    public Docket allApi() {
        return new Docket(DocumentationType.SWAGGER_2)
                .apiInfo(apiInfo())
                .groupName("全部接口")
                .select()
                // 扫描所有带 @RestController 或 @Controller 的类
                .apis(RequestHandlerSelectors.basePackage("com.seeker.tms.biz"))
                .paths(PathSelectors.any())
                .build();
    }


    /**
     * API 信息
     */
    private ApiInfo apiInfo() {
        return new ApiInfoBuilder()
                .title("TMS-API")
                .description("云设备管理-接口文档")
                .contact(new Contact("Seeker", "", "example@163.com"))
                .version("v1.0.0")
                .build();
    }
}

