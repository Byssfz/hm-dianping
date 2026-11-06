package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key,Object value,Long time,TimeUnit unit){
        RedisData redisData=new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(String prefix, ID id, Class<R> type,
                                         Function<ID,R> dbFallback, Long time, Long nullTime, TimeUnit timeUnit){
        String key=prefix+id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        if(json!=null){
            return null;
        }

        R r = dbFallback.apply(id);

        if(r==null){
            this.set(key,"",nullTime,timeUnit);
            return null;
        }

        this.set(key,r,time,timeUnit);

        return r;
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <ID,R> R queryWithLogicalExpire(String prefix,ID id,Class<R> type,
                                            Function<ID,R> dbFallBack,
                                            Long time,TimeUnit timeUnit){
        String key = prefix + id;

        String json = stringRedisTemplate.opsForValue().get(key);

        if(StrUtil.isBlank(json)){
            return null;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);

        if(expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        String lockKey=LOCK_SHOP_KEY+id;

        boolean isLock = tryLock(lockKey);

        if(isLock){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                   this.setWithLogicalExpire(key,r,time,timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        return r;
    }

    private boolean tryLock(String key){
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }



}
