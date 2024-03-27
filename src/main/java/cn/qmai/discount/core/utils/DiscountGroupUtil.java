/*
 * Copyright 2022 Shiyafeng
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.qmai.discount.core.utils;
import cn.qmai.discount.core.model.common.DiscountGroup;
import cn.qmai.discount.core.model.common.DiscountWrapper;
import cn.qmai.discount.core.model.common.Item;
import cn.qmai.discount.core.permutation.Permutation;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.AtomicLongMap;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.tuple.Pair;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ID生成器，用于生成每次计算的商品id
 * 非线程安全，不可以在多线程共享。 每次计算创建一个，用于生成具体每一个商品的唯一ID
 * @author: shiyafeng
 * @date: 2022/7
 */
public class DiscountGroupUtil {

    private static final String EXCLUDE="exclude";

    /**
     * 返回所有共享组
     * Pair的left是长度不超过 Permutation.SUPPORTEDSIZE 的内容，right是第 Permutation.SUPPORTEDSIZE+1 种优惠即其他优惠（若有），即不参与全排列的优惠
     * @param groups 共享互斥协议 DiscountGroup
     * @param inMap 用户当前可用优惠：优惠类型 -> (优惠的ID -> 优惠类)
     * @return
     */
    public static List<Pair<Set<DiscountWrapper>,Set<DiscountWrapper>>> transform(List<List<DiscountGroup>> groups, Map<String, Map<String,DiscountWrapper>> inMap) {
        List<Pair<Set<DiscountWrapper>,Set<DiscountWrapper>>> resultList = Lists.newArrayList();
        // 将用户当前优惠券，构建出所有优惠组合分组
        List<List<Item>> list = mergeGroups(groups,inMap);
        if(CollectionUtils.isEmpty(list)){
            return Lists.newArrayList();
        }
        for(List<Item> items:list){
            // 取出一组优惠组合
            Set<DiscountWrapper> discountWrappers = items.stream().map(x->{
                if(inMap.containsKey(x.getType())){
                    Map<String,DiscountWrapper> m=inMap.get(x.getType());
                    if(m.containsKey(x.getId())){
                        return m.get(x.getId());
                    }
                }
                return null;
            }).filter(Objects::nonNull).collect(Collectors.toSet());
            // 当前可用优惠组合，通过 Pair 左右平衡处理。
            if(CollectionUtils.isNotEmpty(discountWrappers)){
                if(discountWrappers.size() > Permutation.SUPPORTEDSIZE) {
                    // 当前可用优惠组合中优惠数量大于 Permutation.SUPPORTEDSIZE 时，
                    // 平衡处理，将一定会用的优惠存入 left, 其他存入 right
                    resultList.add(balanceLR(discountWrappers));
                } else {
                    resultList.add(Pair.of(discountWrappers, Sets.newHashSet()));
                }
            }
        }
        //优先计算短的共享组
        return resultList.stream().sorted(Comparator.comparing(x->x.getLeft().size(),Collections.reverseOrder())).collect(Collectors.toList());
    }

    /**
     * 保证mustuse的优惠进入left集合
     * @param discountWrappers 当前可用优惠
     */
    private static Pair<Set<DiscountWrapper>,Set<DiscountWrapper>> balanceLR(Set<DiscountWrapper> discountWrappers){
        Set<DiscountWrapper> left=Sets.newHashSet();
        Set<DiscountWrapper> right=Sets.newHashSet();
        for(DiscountWrapper wrapper:discountWrappers){
            if(wrapper.isMustUse()){
                left.add(wrapper);
            }else{
                right.add(wrapper);
            }
        }
        if(left.size()<Permutation.SUPPORTEDSIZE){
            int c = Permutation.SUPPORTEDSIZE-left.size();
            Iterator<DiscountWrapper> it = right.iterator();
            while (c>0&&it.hasNext()){
                DiscountWrapper w = it.next();
                left.add(w);
                it.remove();
                c--;
            }
        }else if(left.size()>Permutation.SUPPORTEDSIZE){
            int c = left.size()-Permutation.SUPPORTEDSIZE;
            Iterator<DiscountWrapper> it = left.iterator();
            while (c>0&&it.hasNext()){
                DiscountWrapper w = it.next();
                right.add(w);
                it.remove();
                c--;
            }
        }
        return Pair.of(left,right);
    }

    /**
     * 根据多个 共享互斥协议组，和用户当前可用优惠，构建出的所有不同的优惠组合分组。
     * 例：用户可用券：1，2，3，4，5
     *  共享互斥协议组：1,2 共享；3，4，5 互斥时，构建的结果为 [1, 2, 3], [1, 2, 4]
     *  共享互斥协议组：1,2 互斥；3，4，5 互斥时，构建的结果为 [1, 3], [1, 4], [2, 3], [2, 4]
     * @param groups 所有规则大组。即多个 共享互斥协议组
     * @param inMap 实际可用优惠
     * @return
     */
    private static List<List<Item>> mergeGroups(List<List<DiscountGroup>> groups,Map<String, Map<String,DiscountWrapper>> inMap){
        if(CollectionUtils.isEmpty(groups) || MapUtils.isEmpty(inMap)){
            return null;
        }
        //resultList 接收最终的结果
        List<List<Item>> resultList = Lists.newArrayList();
        //ctx 全局计数器，对于独立共享组多次被使用进行删除（唯一需要去重的场景）
        AtomicLongMap<String> ctx = AtomicLongMap.create();
        //索引，存放可能需要删除的key。只有共享组时，会记录idxMap
        // key: 共享组生成的 key； value:共享组构建优惠组合位置下标
        Map<String,Integer> idxMap = Maps.newHashMap();
        for(List<DiscountGroup> list:groups){
            // 根据共享互斥协议组及用户当前可用优惠，将用户当前可用优惠合并分组构建不同的优惠组合分组。
            // resultList：构建出的不同的优惠组合分组。用户可用券：1，2，3，4，5
            // 如：1,2 共享；3，4，5 互斥时，构建的结果为 [1, 2, 3], [1, 2, 4]
            // 如：1,2 互斥；3，4，5 互斥时，构建的结果为 [1, 3], [1, 4], [2, 3], [2, 4]
            mergeGroup(list,inMap,ctx,idxMap,resultList);
        }

        /*
        存在只有共享组协议，和共享组和互斥组协议，且共享组一致时，构建优惠组合需要删除只根据共享组构建的优惠组合
        如：
        共享互斥协议组1：[1, 2]共享，[3, 4]互斥
        共享互斥协议组2：[1, 2]共享
        两个共享协议组共同构建所有优惠组合，共享互斥协议组2 构建的优惠组合可以删除
         */
        Map<String,Long> map = ctx.asMap(); // 共享互斥协议组中，共享组被引用次数。key: 共享组生成的 key；value: 被引用次数
        List<Integer> orderedList = Lists.newArrayList();
        for(Map.Entry<String,Long> e:map.entrySet()){
            Integer idx = idxMap.get(e.getKey());
            if(Objects.nonNull(idx)&&e.getValue()>1){
                orderedList.add(idxMap.get(e.getKey()));
            }
        }
        orderedList.sort(Collections.reverseOrder());
        //从后往前删除，否则索引会出问题
        for(Integer i:orderedList){
            resultList.remove(i.intValue());
        }
        // 返回所有的优惠组合
        return resultList.stream().filter(CollectionUtils::isNotEmpty).collect(Collectors.toList());
    }

    /**
     * 共享互斥协议组及用户当前可用优惠，将用户当前可用优惠构建不同的优惠组合分组。
     * @param groups 共享互斥协议组
     * @param inMap 用户当前可用优惠
     * @param ctx 共享互斥协议组中，若存在共享组，则共享组引用次数
     * @param idxMap
     * @param resultList 共享互斥协议组及用户当前可用优惠，组成的优惠组合信息。即一组 List<Item> 是一个优惠组合
     */
    private static void mergeGroup(List<DiscountGroup> groups,
            Map<String, Map<String, DiscountWrapper>> inMap,
            AtomicLongMap<String> ctx,
            Map<String,Integer> idxMap,
            List<List<Item>> resultList){
        if(CollectionUtils.isEmpty(groups)){
            return ;
        }
        // 共享互斥协议是一个数组，数组中最多有2个对象，最少1个对象，若只有1个对象，则该对象必然为共享组，即组内优惠可以叠加使用
        // 根据共享协议以及用户当前可用优惠券，取用户协议组内用户当前可用优惠信息
        List<Item> xList = groups.get(0).filterItems(inMap);
        if(CollectionUtils.isEmpty(xList)){
            return ;
        }
        if(groups.size()==1){
            //必然是共享组
            //存入全局上下文，去重时使用，这里累计次数，若次数大于1，需要在最外层移除此共享组
            String key = uniqueKey(xList);
            ctx.incrementAndGet(key); // 记录共享组被引用的次数
            //记录索引,以便删除
            idxMap.put(key,resultList.size());
            resultList.add(xList);
        }else{
            //yList必然是互斥的
            List<Item> yList = groups.get(1).filterItems(inMap); // 互斥组中：当前用户可用优惠

            // groups.size() > 1 时，若 groups[0] 为互斥组
            if(Objects.equals(EXCLUDE,groups.get(0).getRelation())){
                //产品设计规避了重复的情况，无需去重
                for(Item item:xList){
                    for(Item item1:yList){
                        // 两个互斥组中分别取一个可用优惠，组成一组优惠信息。即两个优惠信息取至两个互斥组
                        resultList.add(Lists.newArrayList(item,item1));
                    }
                }
            }
            // groups[1] 为互斥组协议：yList 为互斥组：当前用户可用优惠
            else{
                //存入全局上下文，去重时使用，这里累计次数，若次数大于1，需要在最外层移除此共享组
                String k = uniqueKey(xList);
                for(Item item:yList){ // 互斥组：和共享组一起构建一组优惠组合
                    ctx.incrementAndGet(k); // 记录共享组被引用的次数
                    // 共享组 + 互斥中的一条记录构建一组优惠组合
                    List<Item> xCopy = Lists.newArrayList(xList);
                    xCopy.add(item);
                    resultList.add(xCopy);
                   }
            }
        }
    }

    private static String uniqueKey(List<Item> list){
        return list.stream().map(i->i.getType()+i.getId()).sorted().collect(Collectors.joining());
    }

}
