package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * @author tianchi
 * 封装缓存工具类
 */

@Slf4j
@Component
public class CacheClient {
    @Resource
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //1. 方法1,将任意java对象序列化为json并存储在string类型的key中,并可以设置TTL过期时间
    public void set(String key, Object value, Long time, TimeUnit unit){
        //将value序列化为string对象
        String temp = JSONUtil.toJsonStr(value);
        stringRedisTemplate.opsForValue().set(key,temp,time,unit);
    }
    //2. 方法2,将任意java对象序列化为json并存储在string类型的key中,并可以设置逻辑过期时间,用于处理缓存击穿问题
    //逻辑过期代表着永远不过期
    public void setLogicExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisData.setData(value);

        //将value序列化为string对象
        //写入redis中
        String temp = JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(key,temp,time,unit);
    }
    //3. 方法3,根据指定的key查询缓存,并反序列化为指定类型,利用缓存空值的方式解决缓存击穿问题
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                         Function<ID,R> dbFallback,
                         Long time,TimeUnit unit){
        String key = keyPrefix + id;
        //1.从redis中查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(json)){
            //3.存在,则直接返回
            return JSONUtil.toBean(json,type);
        }
        //4.判断是否是空值
        if(json == null){
            return null;
        }
        //5.根据id查询数据库
        R r = dbFallback.apply(id);
        //6.不存在,返回错误,并将空值写入redis
        if(r == null){
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",30L,TimeUnit.MINUTES);
            return null;
        }
        //7.存在,写入redis
        this.set(key,r,time,unit);

        return r;
    }
    //4. 方法4,根据指定的key查询缓存,并反序列化为指定类型,需要利用逻辑过期解决缓存击穿问题
    public <R,ID> R queryWithLogicalExpire (String keyPrefix, ID id, Class<R> type,
                                         Function<ID,R> dbFallback,
                                         Long time,TimeUnit unit){
        String key = keyPrefix + id;
        //1.从redis中查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isBlank(json)){
            //3.存在,则直接返回
            return null;
        }
        //4.命中,需要先将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json,RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1 未过期,直接返回店铺信息
            return r;
        }
        //5.2 已过期,需要缓存重建
        //6.缓存重建
        //6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //6.2 判断是否获取锁成功
        if(isLock){
            //6.3成功,开启独立线程,实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                //查询数据库
                R r1 = dbFallback.apply(id);
                //写入redis
                try {
                    this.setLogicExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        return r;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 30, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
}
