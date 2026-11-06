package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private Cache<Long, Shop> shopLocalCache;
    @Override
    public Result queryByID(Long id) {
        Shop shop = shopLocalCache.getIfPresent(id);
        if (shop == null) {
            shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById,
                    CACHE_SHOP_TTL, CACHE_NULL_TTL, TimeUnit.MINUTES);
            if (shop != null) {
                shopLocalCache.put(id, BeanUtil.copyProperties(shop, Shop.class));
            }
        }

        if(shop==null){
            return Result.fail("店铺不存在! ");
        }
        // Do not expose the mutable instance held in the shared local cache.
        return Result.ok(BeanUtil.copyProperties(shop, Shop.class));
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;

        String shopJson = stringRedisTemplate.opsForValue().get(key);

        if(StrUtil.isBlank(shopJson)){
           return null;
        }

        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);

        if(expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        String lockKey=LOCK_SHOP_KEY+id;

        boolean isLock = tryLock(lockKey);

        if(isLock){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        return shop;
    }
    private Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;

        String shopJson = stringRedisTemplate.opsForValue().get(key);

        if(StrUtil.isNotBlank(shopJson)){
           return JSONUtil.toBean(shopJson, Shop.class);
        }

        if(shopJson!=null){
            //return Result.fail("店铺不存在! ");
            return null;
        }

        Shop shop = null;

        String lockKey=LOCK_SHOP_KEY+id;

        boolean isLock=tryLock(lockKey);
        try {
            if(!isLock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //双检 获取锁成功，判断缓存是否重建，防止堆积的线程全部请求数据库（所以说双检是很有必要的）
            shopJson = stringRedisTemplate.opsForValue().get(key);

            if(StrUtil.isNotBlank(shopJson)){
                return JSONUtil.toBean(shopJson, Shop.class);
            }

            if(shopJson!=null){
                //return Result.fail("店铺不存在! ");
                return null;
            }

            shop = getById(id);
            Thread.sleep(200);
            if(shop==null){
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                //return  Result.fail("店铺不存在! ");
                return null;
            }

            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        finally {
            unLock(LOCK_SHOP_KEY);
        }
        return shop;
    }
    private Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;

        String shopJson = stringRedisTemplate.opsForValue().get(key);

        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            //return Result.ok(shop);
            return shop;
        }

        if(shopJson!=null){
            //return Result.fail("店铺不存在! ");
            return null;
        }

        Shop shop = getById(id);

        if(shop==null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            //return  Result.fail("店铺不存在! ");
            return null;
        }

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }
        if (!updateById(shop)) {
            return Result.fail("店铺不存在或更新失败");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
                } catch (RuntimeException e) {
                    // The database has already committed; report the cache failure and still clear local data.
                    log.error("删除商铺 Redis 缓存失败，商铺 id={}", id, e);
                } finally {
                    shopLocalCache.invalidate(id);
                }
            }
        });
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if(x==null||y==null){
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        int from=(current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end=current*SystemConstants.DEFAULT_PAGE_SIZE;
        String key=SHOP_GEO_KEY+typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(key, GeoReference.fromCoordinate(x, y), new Distance(5000)
                , RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        if(results==null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size()<=from){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids=new ArrayList<>(list.size());
        Map<String,Distance> distanceMap=new HashMap<>(list.size());
        list.stream().skip(from).forEach(geoLocationGeoResult -> {
            String shopIdStr = geoLocationGeoResult.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));

            distanceMap.put(shopIdStr,geoLocationGeoResult.getDistance());
        });
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);


    }

    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        Shop shop = getById(id);
        Thread.sleep(200L);
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    private boolean tryLock(String key){
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
}
