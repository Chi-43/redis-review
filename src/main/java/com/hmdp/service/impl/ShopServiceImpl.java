package com.hmdp.service.impl;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    @Override
    public Result queryById(Long id) {
        //缓存击穿
//        Shop shop = queryWithPassThrough(id);

        //使用互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        //使用逻辑过期解决缓存击穿问题
        return Result.ok(shop);
    }

    //使用逻辑过期解决缓存击穿问题
    public Shop queryWithMLogicalExpire(Long id) {
        String key = "cache:shop:" + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        
        if(StrUtil.isBlank(shopJson)){
            //3.未存在,直接返回
            return null;
        }
        //4.命中,需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期,直接返回店铺信息
            return shop;
        }
        //5.2已过期,需要缓存重建
        //6.缓存重建
        //6.1获取互斥锁
        String lockKey = "lock:key:" +id;
        boolean isLock = tryLock(lockKey);
        //6.2判断是否获取锁成功
        if(isLock){
            //TODO 6.3成功,开启独立线程,实现缓存重建
            //使用线程池实现
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.savaShop2Redis(id, 30L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally{
                    //释放锁
                    unLock(lockKey);
                }
            });

        }
        //6.4返回过期的店铺信息
        return shop;
    }

    //使用互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id) {
        String key = "cache:shop:" + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3.存在,直接返回
            return JSONUtil.toBean(shopJson,Shop.class);
        }
        //2.1判断命中的是否是空值
        if(shopJson != null){
            //返回错误信息
            return null;
        }
        //4.实现换成重建
        //4.1获取互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2判断是否获取成功
            if(!isLock){
                //4.3失败,休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);//递归重试
            }
            //4.4成功,根据id查询数据库
            shopJson = stringRedisTemplate.opsForValue().get(key);
            //4.4.1判断是否存在
            if(StrUtil.isNotBlank(shopJson)){
                //4.4.2存在,直接返回
                return JSONUtil.toBean(shopJson,Shop.class);
            }
            shop = getById(id);
            //5.不存在,返回null
            if(shop == null){
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key,"",30,TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }
            //6.存在,写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),30,TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //7.释放互斥锁
            unLock(key);
        }
        //8.返回
        return shop;
    }

    //有缓存击穿问题
    public Shop queryWithPassThrough(Long id) {
        String key = "cache:shop:" + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3.存在,直接返回
            return JSONUtil.toBean(shopJson,Shop.class);
        }
        //2.1判断命中的是否是空值
        if(shopJson != null){
            //返回错误信息
            return null;
        }
        //4.不存在,根据id查询数据库
        Shop shop = getById(id);
        //5.不存在,返回错误
        if(shop == null){
            //5.1将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",2L,TimeUnit.MINUTES);
            return null;
        }
        //6.存在,写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),30L, TimeUnit.MINUTES);
        //7.返回
        return shop;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 30, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    private void savaShop2Redis(Long id,Long expireSeconds){//重建缓存
        //1.查询店铺数据
        Shop shop = getById(id);
        //2.封装过期逻辑时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入redisData
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional // 保证原子性
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.ok("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete("cache:shop:" + id);
        return Result.ok();
    }
}
