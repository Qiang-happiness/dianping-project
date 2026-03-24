package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long expireTime, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(expireTime)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(
            String keyPrefix , ID id, Class<R> type, Function<ID, R> dbFallBack, Long expireTime, TimeUnit unit) {
        //从redis中获取
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //存在，返回查询结果
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }

        if (json != null) {
            return null;
        }

        //不存在，查数据库
        R r = dbFallBack.apply(id);
        //数据库不存在，返回查询失败
        if (r == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //店铺存在，将数据保存到redis
        this.set(key, r, expireTime, unit);
        return r;
    }

    //创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //尝试获取互斥锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放互斥锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, String lockKeyPreFix, Function<ID, R> dbFallBack, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String redisDataJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(redisDataJson)) {
            return null;
        }

        RedisData redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (LocalDateTime.now().isBefore(expireTime)) {
            //未过期，返回
            return r;
        }
        //过期，尝试获取锁
        String lockKey = lockKeyPreFix + id;
        boolean isLock = tryLock(lockKey);
        //获取锁成功，开启独立新线程，完成缓存重建
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    //1.查数据库
                    R r1 = dbFallBack.apply(id);
                    //2.写Redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //返回
        return r;
    }
}
