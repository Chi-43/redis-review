package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    private LocalDateTime expireTime;//过期时间 
    private Object data;//想存进redis中的相关数据
}
