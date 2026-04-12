package com.seeker.tms.common.config;

import com.seeker.tms.common.enums.BoolStatus;
import com.seeker.tms.common.enums.DeviceSys;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 处理接口字段是枚举类型的数据转换
 */
@Configuration
public class EnumConverterConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        // 添加DeviceSys枚举转换器
        registry.addConverter(new Converter<String, DeviceSys>() {
            @Override
            public DeviceSys convert(String source) {
                if (source.trim().isEmpty()) {
                    return null;
                }

                String value = source.trim();

                // 尝试通过name值转换
                for (DeviceSys deviceSys : DeviceSys.values()) {
                    if (deviceSys.getName().equalsIgnoreCase(value)) {
                        return deviceSys;
                    }
                }

                // 如果通过name不匹配，尝试通过枚举名称转换
                try {
                    return DeviceSys.valueOf(value.toUpperCase());
                } catch (IllegalArgumentException ex) {
                    // 如果都不匹配，返回null
                    return null;
                }
            }
        });

        // 添加Bool枚举转换器
        registry.addConverter(new Converter<String, BoolStatus>() {
            @Override
            public BoolStatus convert(String source) {
                if (source.trim().isEmpty()) {
                    return null;
                }

                String value = source.trim();

                // 尝试通过code值转换
                try {
                    int code = Integer.parseInt(value);
                    for (BoolStatus BoolStatus : BoolStatus.values()) {
                        if (BoolStatus.getCode() == code) {
                            return BoolStatus;
                        }
                    }
                } catch (NumberFormatException e) {
                    // 如果不是数字，尝试通过枚举名称转换
                    try {
                        return BoolStatus.valueOf(value.toUpperCase());
                    } catch (IllegalArgumentException ex) {
                        // 如果都不匹配，返回null
                        return null;
                    }
                }
                return null;
            }
        });
    }
}