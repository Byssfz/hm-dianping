package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        //用String来存数据
        String shopTypeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
        if(StrUtil.isNotBlank(shopTypeJson)){
            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            //这里存入就是按照顺序，所以无需排序
            shopTypeList.sort((shopType1,shopType2)->{
                return shopType1.getSort()-shopType2.getSort();
            });
            return Result.ok(shopTypeList);
        }
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        if (shopTypeList==null)
        {
            return Result.fail("店铺类型不存在! ");
        }
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY,JSONUtil.toJsonStr(shopTypeList));
        return Result.ok(shopTypeList);

        /*//用list来存数据
        List<String> shopTypeJsonList = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY, 0, -1);
        if(CollectionUtil.isNotEmpty(shopTypeJsonList)){
            *//*List<ShopType> shopTypeList=new ArrayList<>();
            for (String shopTypeJson : shopTypeJsonList) {
                ShopType shopType = JSONUtil.toBean(shopTypeJson, ShopType.class);
                shopTypeList.add(shopType);
            }*//*
            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeJsonList.toString(), ShopType.class);
            return Result.ok(shopTypeList);
        }
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();

        if (shopTypeList==null)
        {
            return Result.fail("店铺类型不存在! ");
        }


        List<String> jsonList = shopTypeList.stream()
                .map(JSONUtil::toJsonStr)
                .collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_TYPE_KEY, jsonList);  // 传List<String>

        return Result.ok(shopTypeList);
    }*/


        /*// opsForList写法
        // public static final String CACHE_SHOP_TYPE_KEY = "cache:shopType:";
        String key = CACHE_SHOP_TYPE_KEY;
        // 1. 从Redis查询 商铺类型缓存 , end:-1 表示取全部数据
        List<String> shopTypeJson = stringRedisTemplate.opsForList().range(key, 0, -1);
        // 2. 有就直接返回
        if (CollectionUtil.isNotEmpty(shopTypeJson)) {
            // JSON字符串转对象 排序后返回
            List<ShopType> shopTypes = JSONUtil.toList(shopTypeJson.toString(), ShopType.class);
            Collections.sort(shopTypes, ((o1, o2) -> o1.getSort() - o2.getSort()));
            return Result.ok(shopTypes);
        }
        // 3. 没有就向数据库查询 MP的query()拿来用
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        // 4. 不存在，返回错误
        if (CollectionUtil.isEmpty(shopTypes)) {
            return Result.fail("商铺类型不存在...");
        }
        // 5. 存在， 写入Redis，这里使用Redis的List类型，String类型，就是直接所有都写在一起，对内存开销比较大。
        // 要将List中的每个元素(元素类型ShopType) ，每个元素都要单独转成JSON，使用stream流的map映射
        // Hutools里的 BeanUtil.copyToList 本来想模仿UserService中的写法，
        // 传入一个CopyOptions的，但是setFieldValueEditor貌似只对beanToMap有效
        // 改用流的形式转换每个list元素
        List<String> shopTypesJson = shopTypes.stream()
                .map(shopType -> JSONUtil.toJsonStr(shopType))
                .collect(Collectors.toList());
        // 因为从数据库读出来的时候已经是按照顺序读出来的，这里想要维持顺序必须从右边push，类似队列
        stringRedisTemplate.opsForList().rightPushAll(key, shopTypesJson);
        // 5. 返回
        return Result.ok(shopTypes);*/

    }
}
