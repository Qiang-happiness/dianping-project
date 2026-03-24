package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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

import java.util.List;

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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryList() {
        //从redis缓存中查店铺类型
        String typeList = stringRedisTemplate.opsForValue().get(LIST_SHOP_TYPE);
        //存在，返回类型信息
        if (StrUtil.isNotBlank(typeList)) {
            List<ShopType> shopTypeList = JSONUtil.toList(typeList, ShopType.class);
            return Result.ok(shopTypeList);
        }
        //不存在，查数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        //数据库不存在，返回异常信息
        if (shopTypeList.isEmpty()) return Result.fail("出错啦！请联系管理员~");
        //存在，保存到redis
        String shopTypeJson = JSONUtil.toJsonStr(shopTypeList);
        stringRedisTemplate.opsForValue().set(LIST_SHOP_TYPE, shopTypeJson);
        return Result.ok(shopTypeList);
    }
}
