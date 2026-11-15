package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RocketMQTemplate rocketMQTemplate;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //在类初始化之后执行，因为当这个类初始化好了之后，随时都是有可能要执行的

/*    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }*/

    //private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();

    /*    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
        private class VoucherOrderHandler implements Runnable {
            @Override
            public void run() {
                while (true) {
                    try {
                        VoucherOrder voucherOrder = orderTasks.take();
                        handleVoucherOrder(voucherOrder);
                    } catch (InterruptedException e) {
                        log.error("处理订单异常", e);
                    }
                }
            }
        }*/
        /*private class VoucherOrderHandler implements Runnable{
            String queueName = "stream.orders";
            @Override
            public void run() {
                while (true){

                    try {
                        List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                                StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2))
                                , StreamOffset.create(queueName, ReadOffset.lastConsumed()));
                        if(list==null||list.isEmpty()){
                            continue;
                        }
                        MapRecord<String, Object, Object> record = list.get(0);
                        Map<Object, Object> value = record.getValue();
                        VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                       handleVoucherOrder(voucherOrder);

                        stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                    } catch (Exception e) {
                        log.error("处理订单异常", e);
                        //处理异常消息
                        handlePendingList();
                    }

                }
            }

        private void handlePendingList() {
            while (true){
                try {
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1)
                            , StreamOffset.create(queueName, ReadOffset.from("0")));
                    if(list==null||list.isEmpty()){
                        break;
                    }

                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    handleVoucherOrder(voucherOrder);

                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理pendding订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }

        }
    }*/

        //private IVoucherOrderService proxy;

        public void handleVoucherOrder(VoucherOrder voucherOrder) {
            Long userId = voucherOrder.getUserId();
            RLock lock = redissonClient.getLock("order:" + userId);
            boolean isLock = lock.tryLock();
            if (!isLock) {
                throw new IllegalStateException("order lock busy; retry message");
            }
            try {
                IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
                proxy.createVoucherOrder(voucherOrder);
            } finally {
                lock.unlock();
            }
        }

        //异步
        //这里redis是单线程的，所以不用加锁
        @Override
        public Result secKillVoucher(Long voucherId) {
            Long userId = UserHolder.getUser().getId();
            long orderId = redisIdWorker.nextId("order");
            Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.EMPTY_LIST, voucherId.toString(), userId.toString(),String.valueOf(orderId));
            int r = result.intValue();
            // 2.判断结果是否为0
            if (r != 0) {
                // 2.1.不为0 ，代表没有购买资格
                return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
            }
            // The Redis stream intent is dispatched to RocketMQ asynchronously.
            return Result.ok(Long.toString(orderId));
        }

            //stream
/*        @Override
        public Result secKillVoucher(Long voucherId) {
            Long userId = UserHolder.getUser().getId();
            long orderId = redisIdWorker.nextId("order");
            Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.EMPTY_LIST, voucherId.toString(), userId.toString(),String.valueOf(orderId));
            int r = result.intValue();
            // 2.判断结果是否为0
            if (r != 0) {
                // 2.1.不为0 ，代表没有购买资格
                return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
            }
            proxy = (IVoucherOrderService) AopContext.currentProxy();
            return Result.ok(orderId);
        }*/




        //阻塞
       /* @Override
        public Result secKillVoucher(Long voucherId) {
            Long userId = UserHolder.getUser().getId();

            Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.EMPTY_LIST, voucherId.toString(), userId.toString());
            int r = result.intValue();
            // 2.判断结果是否为0
            if (r != 0) {
                // 2.1.不为0 ，代表没有购买资格
                return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
            }
            // 3.返回订单id
            long orderId = redisIdWorker.nextId("order");
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(orderId);
            voucherOrder.setVoucherId(voucherId);
            voucherOrder.setUserId(userId);
            orderTasks.add(voucherOrder);
            proxy = (IVoucherOrderService) AopContext.currentProxy();
            return Result.ok(orderId);
        }*/


        //同步
   /* @Override
    public Result secKillVoucher(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始");
        }
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束");
        }
        if (voucher.getStock()<1){
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
      *//*  synchronized (userId.toString().intern()){
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }*//*
        //SimpleRedisLock lock =new SimpleRedisLock("order:"+userId,stringRedisTemplate);
        RLock lock=redissonClient.getLock("order:"+userId);
        boolean isLock = lock.tryLock();
        if(!isLock){
            return Result.fail("不允许重复下单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
        finally {
            lock.unlock();
        }
    }*/

        @Transactional
        public void createVoucherOrder(VoucherOrder voucherOrder) {

            Long userId = voucherOrder.getUserId();
            VoucherOrder existing = getById(voucherOrder.getId());
            if (existing != null) {
                if (existing.getUserId().equals(userId) && existing.getVoucherId().equals(voucherOrder.getVoucherId())) return;
                throw new IllegalStateException("order id collision");
            }

            int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();

            if (count > 0) {
                throw new IllegalStateException("another order already exists for user and voucher");
            }

            boolean success = seckillVoucherService.update()
                    .setSql("stock= stock-1")
                    .eq("voucher_id", voucherOrder.getVoucherId())
                    .gt("stock", 0)
                    .update();
            if (!success) {
                throw new IllegalStateException("database stock unavailable; reconcile reservation");
            }
            if (!save(voucherOrder)) throw new IllegalStateException("order save failed");
        }
}

