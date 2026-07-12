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

                for (DeviceSys deviceSys : DeviceSys.values()) {
                    if (deviceSys.getName().equalsIgnoreCase(value)) {
                        return deviceSys;
                    }
                }

                try {
                    return DeviceSys.valueOf(value.toUpperCase());
                } catch (IllegalArgumentException ex) {
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

                try {
                    int code = Integer.parseInt(value);
                    for (BoolStatus BoolStatus : BoolStatus.values()) {
                        if (BoolStatus.getCode() == code) {
                            return BoolStatus;
                        }
                    }
                } catch (NumberFormatException e) {
                    try {
                        return BoolStatus.valueOf(value.toUpperCase());
                    } catch (IllegalArgumentException ex) {
                        return null;
                    }
                }
                return null;
            }
        });
    }
}