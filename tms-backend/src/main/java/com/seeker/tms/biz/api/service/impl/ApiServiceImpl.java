package com.seeker.tms.biz.api.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.seeker.tms.biz.api.entities.ApiAddDTO;
import com.seeker.tms.biz.api.entities.ApiInvokeDTO;
import com.seeker.tms.biz.api.entities.ApiInvokeVO;
import com.seeker.tms.biz.api.entities.ApiPO;
import com.seeker.tms.biz.api.mapper.ApiMapper;
import com.seeker.tms.biz.api.service.ApiService;

import com.seeker.tms.common.utils.Http;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;

@Service
public class ApiServiceImpl extends ServiceImpl<ApiMapper, ApiPO> implements ApiService {
    @Override
    public boolean addApi(ApiAddDTO apiAddDTO) {
        ApiPO apiPo = BeanUtil.copyProperties(apiAddDTO, ApiPO.class);

        apiPo.setCreateTime(LocalDateTime.now());
        apiPo.setUpdateTime(LocalDateTime.now());

        return this.save(apiPo);
    }

    @Override
    public ApiInvokeVO invokeApi(ApiInvokeDTO apiInvokeDTO) {

        // 获取API基本信息
        ApiPO apiPO = getById(apiInvokeDTO.getApiId());
        if (apiPO == null) {
            throw new RuntimeException("接口不存在 apiId = " + apiInvokeDTO.getApiId().toString());
        }

        String host = apiPO.getProto() + "://" + apiPO.getUrl();

        // 接口调用
        try {
            Response response = Http.request(host, apiPO.getMethod(),
                    apiInvokeDTO.getParams(), apiInvokeDTO.getHeaders(), apiInvokeDTO.getData());

            ResponseBody responseBody = response.body();
            if (responseBody != null){
                return new ApiInvokeVO(response.code(), JSON.parseObject(responseBody.string()));
            }
            return new ApiInvokeVO(response.code(), null);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}
