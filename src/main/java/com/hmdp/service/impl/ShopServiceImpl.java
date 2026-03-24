package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //解决缓存穿透------缓存空值
        //Shop shop = queryWithPassThrough(id);
//        Shop shop = cacheClient
//                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //解决缓存击穿----逻辑过期
        //Shop shop = queryWithLogicalExpire(id);
        Shop shop = cacheClient
                .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, LOCK_SHOP_KEY, this::getById, 20L, TimeUnit.SECONDS);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    //创建线程池
//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //逻辑过期解决缓存击穿
    /*public Shop queryWithLogicalExpire(Long id) {
        String shopKey = CACHE_SHOP_KEY + id;
        String redisDataJson = stringRedisTemplate.opsForValue().get(shopKey);
        if (StrUtil.isBlank(redisDataJson)) {
            return null;
        }

        RedisData redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (LocalDateTime.now().isBefore(expireTime)) {
            //未过期，返回
            return shop;
        }
        //过期，尝试获取锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //获取锁成功，开启独立新线程，完成缓存重建
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //返回shop
        return shop;
    }*/

    //互斥锁解决缓存击穿
    /*private Shop queryWithMutex(Long id) {
        String shopKey = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        if (shopJson != null) {
            return null;
        }

        //未命中
        String lockKey = LOCK_SHOP_KEY + id;
        //尝试获取锁
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //获取失败,休眠并重试
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //获取成功,查数据库，写缓存
            shop = getById(id);
            stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            unlock(lockKey);
        }
        return shop;
    }*/

    //通过id查询店铺(缓存空值)——解决缓存击穿
    /*private Shop queryWithPassThrough(Long id) {
        //从redis中获取店铺
        String shopKey = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        //存在，返回查询结果
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        if (shopJson != null) {
            return null;
        }

        //不存在，查数据库
        Shop shop = getById(id);
        //数据库不存在，返回查询失败
        if (shop == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(shopKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //店铺存在，将数据保存到redis
        stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }*/

    //尝试获取互斥锁
    /*private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放互斥锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
*/
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //设置过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //保存到redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) return Result.fail("店铺id不能为空！");
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
