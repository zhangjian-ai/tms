package com.seeker.tms.biz.api.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.seeker.tms.biz.api.entities.ApiAddDTO;
import com.seeker.tms.biz.api.entities.ApiInvokeDTO;
import com.seeker.tms.biz.api.entities.ApiInvokeVO;
import com.seeker.tms.biz.api.entities.ApiPO;

public interface ApiService extends IService<ApiPO> {

    boolean addApi(ApiAddDTO apiAddDTO);

    ApiInvokeVO invokeApi(ApiInvokeDTO apiInvokeDTO);
}
