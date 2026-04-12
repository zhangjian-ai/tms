package com.seeker.tms.biz.api.entities;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ApiInvokeVO {

    private Integer statusCode;

    private Object data;
}
