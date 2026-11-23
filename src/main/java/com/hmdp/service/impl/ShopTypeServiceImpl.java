package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    private final StringRedisTemplate stringRedisTemplate;

    public ShopTypeServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result queryTypeList() {
        String key = "SHOP_TYPE";
        //1.从redis中查找
        String shopType = stringRedisTemplate.opsForValue().get(key);
        //2.不为空则直接返回
        if(StrUtil.isNotBlank(shopType)){
            //将shopType转化为List
            List<ShopType> shopTypeList = JSONUtil.toList(shopType, ShopType.class);
            return Result.ok(shopTypeList);
        }
        //3.为空,从数据库中查找
        List<ShopType> ShopTypes = query().orderByAsc("sort").list();
        //4.数据库中不存在,则直接返回
        if(ShopTypes.isEmpty()) return Result.fail("未查找到商铺信息");
        //5.数据库中存在,写入redis中
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(ShopTypes));
        return Result.ok(ShopTypes);
    }
}
