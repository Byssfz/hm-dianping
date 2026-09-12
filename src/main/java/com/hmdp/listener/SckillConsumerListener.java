package com.hmdp.listener;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
@RocketMQMessageListener(topic = "hmseckillTopic",consumerGroup = "hmseckill-consumer-group")
public class SckillConsumerListener implements RocketMQListener<MessageExt> {
    @Autowired
    IVoucherOrderService voucherOrderService;
    @Override
    public void onMessage(MessageExt messageExt) {
        String msg = new String(messageExt.getBody());
        log.info("收到秒杀信息：{}",msg);
        VoucherOrder voucherOrder = JSONUtil.toBean(msg, VoucherOrder.class);
        voucherOrderService.handleVoucherOrder(voucherOrder);
    }
}
