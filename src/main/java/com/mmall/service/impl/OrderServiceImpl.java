package com.mmall.service.impl;

import com.alipay.api.AlipayResponse;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.demo.trade.config.Configs;
import com.alipay.demo.trade.model.ExtendParams;
import com.alipay.demo.trade.model.GoodsDetail;
import com.alipay.demo.trade.model.builder.AlipayTradePrecreateRequestBuilder;
import com.alipay.demo.trade.model.result.AlipayF2FPrecreateResult;
import com.alipay.demo.trade.service.AlipayTradeService;
import com.alipay.demo.trade.service.impl.AlipayTradeServiceImpl;
import com.alipay.demo.trade.utils.ZxingUtils;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mmall.common.Const;
import com.mmall.common.ServerResponse;
import com.mmall.dao.*;
import com.mmall.pojo.*;
import com.mmall.service.IOrderService;
import com.mmall.util.BigDecimalUtil;
import com.mmall.util.DateTimeUtil;
import com.mmall.util.FTPUtil;
import com.mmall.util.PropertiesUtil;
import com.mmall.vo.OrderItemVo;
import com.mmall.vo.OrderProductVo;
import com.mmall.vo.OrderVo;
import com.mmall.vo.ShippingVo;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.schema.Server;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpSession;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.logging.Logger;

@Service("iOrderService")
@Slf4j
public class OrderServiceImpl implements IOrderService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private PayInfoMapper payInfoMapper;
    @Autowired
    private CartMapper cartMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private ShippingMapper shippingMapper;

    public ServerResponse createOrder(Integer userId, Integer shippingId){

        //从购物车中获取数据
        List<Cart> cartList = cartMapper.selectCheckedCartByUserId(userId);

        //计算这个订单的总价
        ServerResponse serverResponse = this.getCartOrderItem(userId,cartList);
        if(!serverResponse.isSuccess()){
            return serverResponse;
        }
        List<OrderItem> orderItemList = (List<OrderItem>)serverResponse.getData();
        BigDecimal payment = this.getOrderTotalPrice(orderItemList);

        //生成订单
        Order order = this.assembleOrder(userId,shippingId,payment);
        if(order == null){
            return ServerResponse.createByErrorMessage("生成订单错误");
        }
        if(CollectionUtils.isEmpty(orderItemList)){
            return ServerResponse.createByErrorMessage("购物车为空");
        }
        for(OrderItem orderItem : orderItemList){
            orderItem.setOrderNo(order.getOrderNo());
        }
        //mybatis批量插入
        orderItemMapper.batchInsert(orderItemList);

        //生成成功，需要减少产品的库存
        this.reduceProductStock(orderItemList);
        //清空一下购物车
        this.cleanCart(cartList);

        //返回给前端数据
        //返回订单的明细，把时间戳转成一定格式的时间等等，返回给前端的不能是code了，要是汉字描述，否则前端还得判断一下
        OrderVo orderVo = assembleOrderVo(order,orderItemList);
        return ServerResponse.createBySuccess(orderVo);
    }

    private OrderVo assembleOrderVo(Order order,List<OrderItem> orderItemList){
        OrderVo orderVo = new OrderVo();
        orderVo.setOrderNo(order.getOrderNo());
        orderVo.setPayment(order.getPayment());
        orderVo.setPaymentType(order.getPaymentType());
        orderVo.setPaymentTypeDesc(Const.PaymentTypeEnum.codeOf(order.getPaymentType()).getValue());

        orderVo.setPostage(order.getPostage());
        orderVo.setStatus(order.getStatus());
        orderVo.setStatusDesc(Const.OrderStatusEnum.codeOf(order.getStatus()).getValue());

        orderVo.setShippingId(order.getShippingId());
        Shipping shipping = shippingMapper.selectByPrimaryKey(order.getShippingId());
        if(shipping != null){
            orderVo.setReceiverName(shipping.getReceiverName());
            orderVo.setShippingVo(assembleShippingVo(shipping));
        }

        orderVo.setPaymentTime(DateTimeUtil.dateToStr(order.getPaymentTime()));
        orderVo.setSendTime(DateTimeUtil.dateToStr(order.getSendTime()));
        orderVo.setEndTime(DateTimeUtil.dateToStr(order.getEndTime()));
        orderVo.setCreateTime(DateTimeUtil.dateToStr(order.getCreateTime()));
        orderVo.setCloseTime(DateTimeUtil.dateToStr(order.getCloseTime()));


        orderVo.setImageHost(PropertiesUtil.getProperty("ftp.server.http.prefix"));


        List<OrderItemVo> orderItemVoList = Lists.newArrayList();

        for(OrderItem orderItem : orderItemList){
            OrderItemVo orderItemVo = assembleOrderItemVo(orderItem);
            orderItemVoList.add(orderItemVo);
        }
        orderVo.setOrderItemVoList(orderItemVoList);
        return orderVo;
    }

    private OrderItemVo assembleOrderItemVo(OrderItem orderItem){
        OrderItemVo orderItemVo = new OrderItemVo();
        orderItemVo.setOrderNo(orderItem.getOrderNo());
        orderItemVo.setProductId(orderItem.getProductId());
        orderItemVo.setProductName(orderItem.getProductName());
        orderItemVo.setProductImage(orderItem.getProductImage());
        orderItemVo.setCurrentUnitPrice(orderItem.getCurrentUnitPrice());
        orderItemVo.setQuantity(orderItem.getQuantity());
        orderItemVo.setTotalPrice(orderItem.getTotalPrice());

        orderItemVo.setCreateTime(DateTimeUtil.dateToStr(orderItem.getCreateTime()));
        return orderItemVo;
    }

    private ShippingVo assembleShippingVo(Shipping shipping){
        ShippingVo shippingVo = new ShippingVo();
        shippingVo.setReceiverName(shipping.getReceiverName());
        shippingVo.setReceiverAddress(shipping.getReceiverAddress());
        shippingVo.setReceiverProvince(shipping.getReceiverProvince());
        shippingVo.setReceiverCity(shipping.getReceiverCity());
        shippingVo.setReceiverDistrict(shipping.getReceiverDistrict());
        shippingVo.setReceiverMobile(shipping.getReceiverMobile());
        shippingVo.setReceiverZip(shipping.getReceiverZip());
        shippingVo.setReceiverPhone(shippingVo.getReceiverPhone());
        return shippingVo;
    }

    private void cleanCart(List<Cart> cartList){
        for(Cart cart : cartList){
            cartMapper.deleteByPrimaryKey(cart.getId());
        }
    }

    private void reduceProductStock(List<OrderItem> orderItemList){
        for(OrderItem orderItem : orderItemList){
            Product product = productMapper.selectByPrimaryKey(orderItem.getProductId());
            product.setStock(product.getStock()-orderItem.getQuantity());
            productMapper.updateByPrimaryKeySelective(product);
        }
    }

    private Order assembleOrder(Integer userId, Integer shippingId, BigDecimal payment){
        Order order = new Order();
        long orderNo = this.generateOrderNo();
        order.setOrderNo(orderNo);
        order.setStatus(Const.OrderStatusEnum.NO_PAY.getCode());
        order.setPostage(0);
        order.setPaymentType(Const.PaymentTypeEnum.ONLINE_PAY.getCode());
        order.setPayment(payment);

        order.setUserId(userId);
        order.setShippingId(shippingId);
        //现在还没付款发货，后面会进行更新
        //发货时间等等
        //付款时间等等
        int rowCount = orderMapper.insert(order);
        if(rowCount > 0){
            return order;
        }
        return null;
    }

    //在高并发分布式下订单号的生成非常重要，分库分表
    //订单号用主键竞争对手一看就知道产生了多少单，用日期数字网上增也很容易被看出来网站一天有多少量
    //订单号限制了唯一索引，重复的话会下单失败
    //也可以前一天开一个缓存池生成订单号，第二天从池子里面取
    private long generateOrderNo(){
        long currentTime = System.currentTimeMillis();
        return currentTime + new Random().nextInt(100);
    }

    private BigDecimal getOrderTotalPrice(List<OrderItem> orderItemList){
        BigDecimal payment = new BigDecimal("0");
        for(OrderItem orderItem : orderItemList){
            payment = BigDecimalUtil.add(payment.doubleValue(),orderItem.getTotalPrice().doubleValue());
        }
        return payment;
    }

    private ServerResponse getCartOrderItem(Integer userId, List<Cart> cartList){
        List<OrderItem> orderItemList = Lists.newArrayList();
        if(CollectionUtils.isEmpty(cartList)){
            return ServerResponse.createByErrorMessage("购物车为空");
        }

        //校验购物车的数据，包括产品的状态和数量
        for(Cart cartItem : cartList){
            OrderItem orderItem = new OrderItem();
            Product product = productMapper.selectByPrimaryKey(cartItem.getProductId());
            //小bug,product为空咋办
            if(Const.ProductStatusEnum.ON_SALE.getCode() != product.getStatus()){
                return ServerResponse.createByErrorMessage("产品" + product.getName() + "不是在线售卖状态");
            }

            //检验库存
            if(cartItem.getQuantity() > product.getStock()){
                return ServerResponse.createByErrorMessage("产品" + product.getName() + "库存不足");
            }

            orderItem.setUserId(userId);
            orderItem.setUserId(userId);
            orderItem.setProductId(product.getId());
            orderItem.setProductName(product.getName());
            orderItem.setProductImage(product.getMainImage());
            //商品交易时的价格
            orderItem.setCurrentUnitPrice(product.getPrice());
            orderItem.setQuantity(cartItem.getQuantity());
            orderItem.setTotalPrice(BigDecimalUtil.mul(product.getPrice().doubleValue(),cartItem.getQuantity()));
            orderItemList.add(orderItem);
        }
        return ServerResponse.createBySuccess(orderItemList);
    }

    public ServerResponse<String> cancelOrder(Integer userId, Long orderNo){
        Order order = orderMapper.selectByUserIdAndOrderNo(userId,orderNo);
        if(order == null){
            return ServerResponse.createByErrorMessage("该用户胡订单不存在");
        }
        if(order.getStatus() != Const.OrderStatusEnum.NO_PAY.getCode()){
            return ServerResponse.createByErrorMessage("已付款不可取消");
        }
        Order updateOrder = new Order();
        updateOrder.setId(order.getId());
        updateOrder.setStatus(Const.OrderStatusEnum.CANCELED.getCode());
        int row = orderMapper.updateByPrimaryKeySelective(updateOrder);
        if(row > 0){
            return ServerResponse.createBySuccess();
        }
        return ServerResponse.createByError();
    }

    public ServerResponse getOrderCartProduct(Integer userId){
        OrderProductVo orderProductVo = new OrderProductVo();

        //从购物车中获取数据
        List<Cart> cartList = cartMapper.selectCheckedCartByUserId(userId);
        ServerResponse serverResponse = this.getCartOrderItem(userId,cartList);
        if(!serverResponse.isSuccess()){
            return serverResponse;
        }
        List<OrderItem> orderItemList = (List<OrderItem>) serverResponse.getData();
        List<OrderItemVo> orderItemVoList = Lists.newArrayList();

        BigDecimal payment = new BigDecimal("0");
        for(OrderItem orderItem : orderItemList){
            BigDecimalUtil.add(payment.doubleValue(),orderItem.getTotalPrice().doubleValue());
            orderItemVoList.add(assembleOrderItemVo(orderItem));
        }
        orderProductVo.setProductTotalPrice(payment);
        orderProductVo.setOrderItemVoList(orderItemVoList);
        orderProductVo.setImageHost(PropertiesUtil.getProperty("ftp.server.http.prefix"));

        return ServerResponse.createBySuccess(orderProductVo);
    }

    public ServerResponse<OrderVo> getOrderDetail(Integer userId, Long orderNo){
        Order order = orderMapper.selectByUserIdAndOrderNo(userId, orderNo);
        if(order != null){
            List<OrderItem> orderItemList = orderItemMapper.getByOrderNoUserId(orderNo,userId);
            OrderVo orderVo = assembleOrderVo(order,orderItemList);
            return ServerResponse.createBySuccess(orderVo);
        }
        return ServerResponse.createByErrorMessage("此订单不存在");
    }

    public ServerResponse<PageInfo> getOrderList(Integer userId, @RequestParam(value = "pageNum",defaultValue = "1" ) Integer pageNum, @RequestParam(value = "pageSize",defaultValue = "10" )Integer pageSize){
        PageHelper.startPage(pageNum,pageSize);
        List<Order> orderList = orderMapper.selectByUserId(userId);
        List<OrderVo> orderVoList = assembleOrderVoList(orderList,userId);
        PageInfo pageResult = new PageInfo(orderList);
        pageResult.setList(orderVoList);
        return ServerResponse.createBySuccess(pageResult);
    }

    public List<OrderVo> assembleOrderVoList(List<Order> orderList, Integer userId){
        List<OrderVo> orderVoList = Lists.newArrayList();
        for(Order order : orderList){
            List<OrderItem> orderItemList = Lists.newArrayList();
            if(userId == null){
                //todo 管理员查询时，不需要传userId
                orderItemList = orderItemMapper.getByOrderNo(order.getOrderNo());
            }else{
                orderItemList = orderItemMapper.getByOrderNoUserId(order.getOrderNo(),userId);
            }
            OrderVo orderVo = assembleOrderVo(order,orderItemList);
            orderVoList.add(orderVo);
        }
        return orderVoList;
    }

    public ServerResponse pay(Long orderNo, Integer userId, String path){
        //跟前端的约定是把订单号和付款二维码url传过去,就不重新创建这个对象了，直接用map来承载这个对象
        Map<String,String> resultMap = Maps.newHashMap();
        Order order = orderMapper.selectByUserIdAndOrderNo(userId,orderNo);
        if(order == null){
            return ServerResponse.createByErrorMessage("用户没有该订单");
        }
        resultMap.put("orderNo",String.valueOf(order.getOrderNo()));

        //组装生成支付宝订单的各种参数
        // (必填) 商户网站订单系统中唯一订单号，64个字符以内，只能包含字母、数字、下划线，
        // 需保证商户系统端不能重复，建议通过数据库sequence生成，
        String outTradeNo = order.getOrderNo().toString();

        // (必填) 订单标题，粗略描述用户的支付目的。如“xxx品牌xxx门店当面付扫码消费”
        String subject = new StringBuilder().append("happymmall扫码支付，订单号:").append(outTradeNo).toString();

        // (必填) 订单总金额，单位为元，不能超过1亿元
        // 如果同时传入了【打折金额】,【不可打折金额】,【订单总金额】三者,则必须满足如下条件:【订单总金额】=【打折金额】+【不可打折金额】
        String totalAmount = order.getPayment().toString();

        // (可选) 订单不可打折金额，可以配合商家平台配置折扣活动，如果酒水不参与打折，则将对应金额填写至此字段
        // 如果该值未传入,但传入了【订单总金额】,【打折金额】,则该值默认为【订单总金额】-【打折金额】
        String undiscountableAmount = "0";

        // 卖家支付宝账号ID，用于支持一个签约账号下支持打款到不同的收款账号，(打款到sellerId对应的支付宝账号)
        // 如果该字段为空，则默认为与支付宝签约的商户的PID，也就是appid对应的PID
        String sellerId = "";

        // 订单描述，可以对交易或商品进行一个详细地描述，比如填写"购买商品2件共15.00元"
        String body = new StringBuilder().append("订单").append(outTradeNo).append("购买商品共").append(totalAmount).append("元").toString();

        // 商户操作员编号，添加此参数可以为商户操作员做销售统计
        String operatorId = "test_operator_id";

        // (必填) 商户门店编号，通过门店号和商家后台可以配置精准到门店的折扣信息，详询支付宝技术支持
        String storeId = "test_store_id";

        // 业务扩展参数，目前可添加由支付宝分配的系统商编号(通过setSysServiceProviderId方法)，详情请咨询支付宝技术支持
        ExtendParams extendParams = new ExtendParams();
        extendParams.setSysServiceProviderId("2088100200300400500");

        // 支付超时，定义为120分钟
        String timeoutExpress = "120m";

        // 商品明细列表，需填写购买商品详细信息，
        List<GoodsDetail> goodsDetailList = new ArrayList<GoodsDetail>();

        List<OrderItem> orderItemList = orderItemMapper.getByOrderNoUserId(orderNo,userId);
        for(OrderItem orderItem : orderItemList){
            //商品单价单位为分哟
            GoodsDetail goods = GoodsDetail.newInstance(orderItem.getProductId().toString(),orderItem.getProductName(),
                    BigDecimalUtil.mul(orderItem.getCurrentUnitPrice().doubleValue(),new Double(100).doubleValue()).longValue(),
                    orderItem.getQuantity());
            goodsDetailList.add(goods);
        }

       /* // 创建一个商品信息，参数含义分别为商品id（使用国标）、名称、单价（单位为分）、数量，如果需要添加商品类别，详见GoodsDetail
        GoodsDetail goods1 = GoodsDetail.newInstance("goods_id001", "xxx小面包", 1000, 1);
        // 创建好一个商品后添加至商品明细列表
        goodsDetailList.add(goods1);

        // 继续创建并添加第一条商品信息，用户购买的产品为“黑人牙刷”，单价为5.00元，购买了两件
        GoodsDetail goods2 = GoodsDetail.newInstance("goods_id002", "xxx牙刷", 500, 2);
        goodsDetailList.add(goods2);*/

        // 创建扫码支付请求builder，设置请求参数
        AlipayTradePrecreateRequestBuilder builder = new AlipayTradePrecreateRequestBuilder()
                .setSubject(subject).setTotalAmount(totalAmount).setOutTradeNo(outTradeNo)
                .setUndiscountableAmount(undiscountableAmount).setSellerId(sellerId).setBody(body)
                .setOperatorId(operatorId).setStoreId(storeId).setExtendParams(extendParams)
                .setTimeoutExpress(timeoutExpress)
                .setNotifyUrl(PropertiesUtil.getProperty("alipay.callback.url"))//支付宝服务器主动通知商户服务器里指定的页面http路径,根据需要设置
                .setGoodsDetailList(goodsDetailList);

        /** 一定要在创建AlipayTradeService之前调用Configs.init()设置默认参数
         *  Configs会读取classpath下的zfbinfo.properties文件配置信息，如果找不到该文件则确认该文件是否在classpath目录
         */
        Configs.init("zfbinfo.properties");

        /** 使用Configs提供的默认参数
         *  AlipayTradeService可以使用单例或者为静态成员对象，不需要反复new
         */
        AlipayTradeService tradeService = new AlipayTradeServiceImpl.ClientBuilder().build();

        AlipayF2FPrecreateResult result = tradeService.tradePrecreate(builder);
        switch (result.getTradeStatus()) {
            case SUCCESS:
                log.info("支付宝预下单成功: )");

                AlipayTradePrecreateResponse response = result.getResponse();
                dumpResponse(response);

                //生成二维码，把二维码传到服务器上，组装成url，返回给前端
                File folder = new File(path);
                if(!folder.exists()){
                    folder.setWritable(true);
                    folder.mkdirs();
                }

                // 需要修改为运行机器上的路径
                String qrPath = String.format(path + "/qr-%s.png",response.getOutTradeNo());
                String qrFileName = String.format("/qr-%s.png",response.getOutTradeNo());
                //guava和支付宝封装的生成二维码
                ZxingUtils.getQRCodeImge(response.getQrCode(), 256, qrPath);

                File targetFile = new File(path,qrFileName);
                try {
                    FTPUtil.uploadFile(Lists.newArrayList(targetFile));
                } catch (IOException e) {
                    log.error("上传二维码异常",e);
                }
                log.info("qrPath:" + qrPath);
                String qrUrl = PropertiesUtil.getProperty("ftp.server.http.prefix") + targetFile.getName();
                resultMap.put("qrUrl",qrUrl);
                return ServerResponse.createBySuccess(resultMap);
            case FAILED:
                log.error("支付宝预下单失败!!!");
                return ServerResponse.createByErrorMessage("支付宝预下单失败!!!");
            case UNKNOWN:
                log.error("系统异常，预下单状态未知!!!");
                return ServerResponse.createByErrorMessage("系统异常，预下单状态未知!!!");
            default:
                log.error("不支持的交易状态，交易返回异常!!!");
                return ServerResponse.createByErrorMessage("不支持的交易状态，交易返回异常!!!");
        }
    }

    // 简单打印应答
    private void dumpResponse(AlipayResponse response) {
        if (response != null) {
            log.info(String.format("code:%s, msg:%s", response.getCode(), response.getMsg()));
            if (StringUtils.isNotEmpty(response.getSubCode())) {
                log.info(String.format("subCode:%s, subMsg:%s", response.getSubCode(),
                        response.getSubMsg()));
            }
            log.info("body:" + response.getBody());
        }
    }

    public ServerResponse aliCallback(Map<String,String> params){
        Long orderNo = Long.parseLong(params.get("out_trade_no"));
        //支付宝交易号，要存到payinfo表里，把它持久化掉
        String tradeNo = params.get("trade_status");
        String tradeStatus = params.get("trade_status");
        Order order = orderMapper.selectByOrderNo(orderNo);
        if(order == null){
            return ServerResponse.createByErrorMessage("非本商城订单，回调忽略");
        }
        if(order.getStatus() >= Const.OrderStatusEnum.PAID.getCode()){
            //controller要判断isSuccess，要返回一个success，否则支付宝会重复通知
            return ServerResponse.createBySuccess("支付宝重复调用");
        }

        //待付款的订单不会走这个，但是还是会保存payInfo
        if(Const.AlipayCallback.TRADE_STATUS_TRADE_SUCCESS.equals(tradeStatus)){
            order.setPaymentTime(DateTimeUtil.strToDate(params.get("gmt_payment")));
            order.setStatus(Const.OrderStatusEnum.PAID.getCode());
            orderMapper.updateByPrimaryKeySelective(order);
        }

        PayInfo payInfo = new PayInfo();
        payInfo.setUserId(order.getUserId());
        payInfo.setOrderNo(order.getOrderNo());
        payInfo.setPayPlatform(Const.PayPlatformEnum.ALIPAY.getCode());
        payInfo.setPlatformNumber(tradeNo);
        payInfo.setPlatformStatus(tradeStatus);
        payInfoMapper.insert(payInfo);
        return ServerResponse.createBySuccess();
    }

    public ServerResponse<Boolean> queryOrderPayStatus(Integer userId, Long orderNo){
        Order order = orderMapper.selectByUserIdAndOrderNo(userId,orderNo);
        if(order == null){
            return ServerResponse.createByErrorMessage("用户没有该订单");
        }
        if(order.getStatus() >= Const.OrderStatusEnum.PAID.getCode()){
            return ServerResponse.createBySuccess();
        }
        return ServerResponse.createBySuccess();
    }

    //backend

    public ServerResponse<PageInfo> manageList(int pageNum,int pageSize){
        PageHelper.startPage(pageNum,pageSize);
        List<Order> orderList = orderMapper.selectAllOrder();
        List<OrderVo> orderVoList = this.assembleOrderVoList(orderList,null);
        PageInfo pageResult = new PageInfo(orderList);
        pageResult.setList(orderVoList);
        return ServerResponse.createBySuccess(pageResult);
    }

    public ServerResponse<OrderVo> manageDetail(Long orderNo){
        Order order = orderMapper.selectByOrderNo(orderNo);
        if(order != null){
            List<OrderItem> orderItemList = orderItemMapper.getByOrderNo(orderNo);
            OrderVo orderVo = assembleOrderVo(order,orderItemList);
            return ServerResponse.createBySuccess(orderVo);
        }
        return ServerResponse.createByErrorMessage("订单不存在");
    }

    //现在是根据订单号精确查找，以后还要加上模糊查找手机号查找等，所以先加上分页
    public ServerResponse<PageInfo> manageSearch(Long orderNo,int pageNum,int pageSize){
        PageHelper.startPage(pageNum,pageSize);
        Order order = orderMapper.selectByOrderNo(orderNo);
        if(order != null){
            List<OrderItem> orderItemList = orderItemMapper.getByOrderNo(orderNo);
            OrderVo orderVo = assembleOrderVo(order,orderItemList);

            PageInfo pageResult = new PageInfo(Lists.newArrayList(order));
            pageResult.setList(Lists.newArrayList(orderVo));
            return ServerResponse.createBySuccess(pageResult);
        }
        return ServerResponse.createByErrorMessage("订单不存在");
    }

    public ServerResponse<String> manageSendGoods(Long orderNo){
        Order order= orderMapper.selectByOrderNo(orderNo);
        if(order != null){
            if(order.getStatus() == Const.OrderStatusEnum.PAID.getCode()){
                order.setStatus(Const.OrderStatusEnum.SHIPPED.getCode());
                //发货时间就是现在
                order.setSendTime(new Date());
                orderMapper.updateByPrimaryKeySelective(order);
                return ServerResponse.createBySuccess("发货成功");
            }
        }
        return ServerResponse.createByErrorMessage("订单不存在");
    }

}
