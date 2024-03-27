package cn.qmai.discount.demo.biz;

import cn.qmai.discount.core.enums.GroupRelation;
import cn.qmai.discount.core.model.common.*;
import cn.qmai.discount.core.model.goods.GoodsInfo;
import cn.qmai.discount.core.model.goods.GoodsItem;
import cn.qmai.discount.core.utils.DiscountGroupUtil;
import cn.qmai.discount.core.utils.IdGenerator;
import cn.qmai.discount.core.utils.LimitingUtil;
import cn.qmai.discount.demo.biz.constant.Constant;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.util.stream.IntStream;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import cn.qmai.discount.core.aware.CalculatorRouter;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test")
public class TestController {

    private final CalculatorRouter calculatorRouter;

    public TestController(CalculatorRouter calculatorRouter) {
        this.calculatorRouter = calculatorRouter;
    }

    @GetMapping("/test1")
    @ResponseBody
    public Object test() {
        //mock商品
        List<GoodsItem> items = mockItems();
        //mock组关系并转化为共享组
        List<Pair<Set<DiscountWrapper>,Set<DiscountWrapper>>> pairs = transform(mockGroups());
        //全局最优计算过程
        List<CalcStage> globalStages=Lists.newArrayList();
        int count = 0;
        //订单总金额
        long totalPrice = items.stream().mapToLong(GoodsInfo::getSalePrice).sum();
        long globalPrice = totalPrice;
        //构建计算流
        Flowable flowable = (Flowable) new Flowable().build(calculatorRouter);
        for(Pair<Set<DiscountWrapper>,Set<DiscountWrapper>> set:pairs) {
            //统计算力：待确定原因
            count += LimitingUtil.count(set.getLeft().size());
            if(count>100000){
                break;
            }
            List<DiscountWrapper> wrappers = Lists.newArrayList(set.getLeft());
            DiscountContext<GoodsItem> ctx = DiscountContext.create(totalPrice, Lists.newArrayList(items), wrappers);
            flowable.perm(ctx);
            if(ctx.getCalcResult().getFinalPrice() < globalPrice) {
                globalStages = Arrays.asList(ctx.getCalcResult().getStages());
                globalPrice = ctx.getCalcResult().getFinalPrice();
            }
        }
        return Pair.of(globalPrice,globalStages);
    }

    /**
     * mock 共享互斥协议 DiscountGroup
     * @return
     */
    private List<List<DiscountGroup>> mockGroups(){
        List<List<DiscountGroup>> groups = Lists.newArrayList();
        DiscountGroup group = new DiscountGroup();
        group.setRelation(GroupRelation.SHARE.getType());
        group.setItems(Lists.newArrayList(new Item("zhekou","1"),new Item("manjian","2"),new Item("manzeng","3")));
        groups.add(Lists.newArrayList(group));
        return groups;
    }

    private List<GoodsItem> mockItems(){
        IdGenerator idGenerator = IdGenerator.getInstance();
        GoodsInfo goodsInfo = GoodsInfo.of(1001L,2001L,null,4,20 * 100,"产品1",null);
        GoodsInfo goodsInfo2 = GoodsInfo.of(1001L,2002L,null,2,10 * 100,"产品1",null);
        List<GoodsItem> items = GoodsItem.generateItems(goodsInfo,idGenerator,x->x.getExtra().put(Constant.UPDATEABLEPRICE,x.getSalePrice()));
        items.addAll(GoodsItem.generateItems(goodsInfo2,idGenerator,x->x.getExtra().put(Constant.UPDATEABLEPRICE,x.getSalePrice())));
        return items;
    }

    private List<Pair<Set<DiscountWrapper>,Set<DiscountWrapper>>> transform(List<List<DiscountGroup>> groups){
        // 优惠信息
        List<DiscountWrapper> wrapperList = Lists.newArrayList(
                DiscountWrapper.of("zhekou", "1", "折扣", false, new DiscountConfig()),
                DiscountWrapper.of("manjian", "2", "满减", false, new DiscountConfig())
        );
        // type -> (id -> DiscountWrapper)
        // 优惠类型 -> (优惠的ID -> 优惠类)
        Map<String, Map<String,DiscountWrapper>> inMap = wrapperList.stream().collect(Collectors.toMap(DiscountWrapper::getType, x->ImmutableMap.of(x.getId(),x)));
        return DiscountGroupUtil.transform(groups,inMap);
    }


    public static void main(String[] args) {
        for (int i = 1; i <= 7; i++) {
            List<Byte> collect = IntStream.range(0, i)
                    .boxed()
                    .map(x -> (byte) x.intValue())
                    .collect(Collectors.toList());
            Collection<List<Byte>> permutations = Collections2.permutations(collect);
            System.out.println(permutations);
        }
    }
}
