package com.qingcheng.service.impl;

import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.fastjson.JSON;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.qingcheng.dao.SkuMapper;
import com.qingcheng.entity.PageResult;
import com.qingcheng.pojo.goods.Sku;
import com.qingcheng.pojo.order.OrderItem;
import com.qingcheng.service.goods.SkuService;
import com.qingcheng.utils.CacheKey;
import org.apache.http.HttpHost;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.annotation.Transactional;
import tk.mybatis.mapper.entity.Example;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service(interfaceClass = SkuService.class)
public class SkuServiceImpl implements SkuService {

    @Autowired
    private SkuMapper skuMapper;


    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 返回全部记录
     *
     * @return
     */
    public List<Sku> findAll() {
        return skuMapper.selectAll();
    }

    /**
     * 分页查询
     *
     * @param page 页码
     * @param size 每页记录数
     * @return 分页结果
     */
    public PageResult<Sku> findPage(int page, int size) {
        PageHelper.startPage(page, size);
        Page<Sku> skus = (Page<Sku>) skuMapper.selectAll();
        return new PageResult<Sku>(skus.getTotal(), skus.getResult());
    }

    /**
     * 条件查询
     *
     * @param searchMap 查询条件
     * @return
     */
    public List<Sku> findList(Map<String, Object> searchMap) {
        Example example = createExample(searchMap);
        return skuMapper.selectByExample(example);
    }

    /**
     * 分页+条件查询
     *
     * @param searchMap
     * @param page
     * @param size
     * @return
     */
    public PageResult<Sku> findPage(Map<String, Object> searchMap, int page, int size) {
        PageHelper.startPage(page, size);
        Example example = createExample(searchMap);
        Page<Sku> skus = (Page<Sku>) skuMapper.selectByExample(example);
        return new PageResult<Sku>(skus.getTotal(), skus.getResult());
    }

    /**
     * 根据Id查询
     *
     * @param id
     * @return
     */
    public Sku findById(String id) {
        return skuMapper.selectByPrimaryKey(id);
    }

    /**
     * 新增
     *
     * @param sku
     */
    public void add(Sku sku) {
        skuMapper.insert(sku);
    }

    /**
     * 修改
     *
     * @param sku
     */
    public void update(Sku sku) {
        skuMapper.updateByPrimaryKeySelective(sku);
    }

    /**
     * 删除
     *
     * @param id
     */
    public void delete(String id) {
        skuMapper.deleteByPrimaryKey(id);
    }


    /**
     * @UpdateUser: heiye
     * @UpdateRemark: 将所有商品的价格进行缓存预热
     */
    @Override
    public void saveAllToRedis() {
        //判断redis中是否已经存在价格key因为价格数量过大
        Boolean key = redisTemplate.hasKey(CacheKey.SKU_PROICE);
        if (key == false) {
            List<Sku> skuList = skuMapper.selectAll();
            for (Sku sku : skuList) {
                if ("1".equals(sku.getStatus())) {
                    redisTemplate.boundHashOps(CacheKey.SKU_PROICE).put(sku.getId(), sku.getPrice());
                }
            }
        } else {
            System.out.println("-----Cached data already exists------");
        }
    }


    /**
     * @UpdateUser: heiye
     * @UpdateRemark: 根据id从缓存预热中取出数据
     */
    @Override
    public Integer findByPrice(String id) {
        Integer price = (Integer) redisTemplate.boundHashOps(CacheKey.SKU_PROICE).get(id);
        return price;
    }


    /**
     * @UpdateUser: heiye
     * @UpdateRemark: 更新商品的价格
     */
    @Override
    public void savePriceToRedisById(String id, Integer price) {
        redisTemplate.boundHashOps(CacheKey.SKU_PROICE).put(id, price);
    }

    /**
     * @UpdateUser: heiye
     * @UpdateRemark: 根据id删除缓存中的数据
     */
    @Override
    public void delPriceToRedis(String id) {
        redisTemplate.boundHashOps(CacheKey.SKU_PROICE).delete(id);
    }

    /**
     * @UpdateUser: heiye
     * @UpdateRemark: 输入导入（elasticsearch）
     */
    @Override
    public void importToEs() {

        System.out.println("开始连接索引库");
        HttpHost http = new HttpHost("localhost", 9200, "http");
        RestClientBuilder builder = RestClient.builder(http);//rest构建器
        RestHighLevelClient restHighLevelClient = new RestHighLevelClient(builder);//高级客户端对象 （连接）

        if (countEs(restHighLevelClient) > 0) {
            System.out.println("已存在索引数据");
            return;
        }

        System.out.println("开始准备索引库数据");
        Map map = new HashMap();
        map.put("status", "1");
        int pageNo = 1;
        while (true) {
            System.out.println("page:" + pageNo);
            PageResult page = findPage(map, pageNo, 1000);
            List<Sku> skuList = page.getRows();
            if (skuList.size() > 0) {
                importSkuListToEs(restHighLevelClient, skuList);
            } else {
                break;
            }
            pageNo++;
        }
        System.out.println(".....索引库数据完成");
    }


    /**
     * 库存扣减
     *
     * @param orderItemList
     */
    @Transactional
    @Override
    public boolean  deductionStock(List<OrderItem> orderItemList) {

        //定义是否扣减库存标识
        boolean isDeduction = true;
        //遍历明细
        for (OrderItem orderItem : orderItemList) {
            //查询对应sku
            Sku sku = findById(orderItem.getSkuId());
            //验证是否存在该商品
            if (sku == null) {
                isDeduction = false;
                break;
            }
            //状态是否合法
            if (!"1".equals(sku.getStatus())) {
                isDeduction = false;
                break;
            }
            //库存不足
            if (sku.getNum() < orderItem.getNum()) {
                isDeduction = false;
                break;
            }
        }

        //扣减库存
        if (isDeduction) {
            for (OrderItem orderItem : orderItemList) {
                //扣减库存
                skuMapper.deductionStock(orderItem.getSkuId(), orderItem.getNum());
                //增加销量
                skuMapper.addSaleNum(orderItem.getSkuId(), orderItem.getNum());
            }
        }

        return isDeduction;
    }

    /**
     * 计算当前数据条数
     *
     * @param restHighLevelClient
     * @return
     */
    private long countEs(RestHighLevelClient restHighLevelClient) {
        //2.封装查询请求
        SearchRequest searchRequest = new SearchRequest("sku");
        searchRequest.types("doc"); //设置查询的类型
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchRequest.source(searchSourceBuilder);
        //3.获取查询结果
        SearchResponse searchResponse = null;
        try {
            searchResponse = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);
        } catch (IOException e) {
            e.printStackTrace();
            return -1;
        }
        SearchHits searchHits = searchResponse.getHits();
        long totalHits = searchHits.getTotalHits();
        System.out.println("记录数：" + totalHits);
        return totalHits;
    }

    /**
     * 将sku列表导入索引库
     *
     * @param restHighLevelClient
     * @param skuList
     */
    private void importSkuListToEs(RestHighLevelClient restHighLevelClient, List<Sku> skuList) {
        //2.构建BulkRequest
        BulkRequest bulkRequest = new BulkRequest();
        for (Sku sku : skuList) {
            IndexRequest indexRequest = new IndexRequest("sku", "doc", sku.getId().toString());
            Map skuMap = new HashMap();
            skuMap.put("name", sku.getName());
            skuMap.put("brandName", sku.getBrandName());
            skuMap.put("categoryName", sku.getCategoryName());
            skuMap.put("image", sku.getImage());
            skuMap.put("price", sku.getPrice());
            skuMap.put("createTime", sku.getCreateTime());
            skuMap.put("saleNum", sku.getSaleNum());
            skuMap.put("commentNum", sku.getCommentNum());
            skuMap.put("spec", JSON.parseObject(sku.getSpec(), Map.class));
            indexRequest.source(skuMap);
            bulkRequest.add(indexRequest);
        }
        System.out.println("开始导入索引库");
        //异步调用方式
        restHighLevelClient.bulkAsync(bulkRequest, RequestOptions.DEFAULT, new ActionListener<BulkResponse>() {
            public void onResponse(BulkResponse bulkResponse) {
                //成功
                System.out.println("导入成功" + bulkResponse.status());
            }

            public void onFailure(Exception e) {
                e.printStackTrace();
                //失败
                System.out.println("导入失败" + e.getMessage());
            }
        });
        System.out.println("导入完成");
    }

    /**
     * 构建查询条件
     *
     * @param searchMap
     * @return
     */
    private Example createExample(Map<String, Object> searchMap) {
        Example example = new Example(Sku.class);
        Example.Criteria criteria = example.createCriteria();
        if (searchMap != null) {
            // 商品id
            if (searchMap.get("id") != null && !"".equals(searchMap.get("id"))) {
                criteria.andLike("id", "%" + searchMap.get("id") + "%");
            }
            // 商品条码
            if (searchMap.get("sn") != null && !"".equals(searchMap.get("sn"))) {
                criteria.andLike("sn", "%" + searchMap.get("sn") + "%");
            }
            // SKU名称
            if (searchMap.get("name") != null && !"".equals(searchMap.get("name"))) {
                criteria.andLike("name", "%" + searchMap.get("name") + "%");
            }
            // 商品图片
            if (searchMap.get("image") != null && !"".equals(searchMap.get("image"))) {
                criteria.andLike("image", "%" + searchMap.get("image") + "%");
            }
            // 商品图片列表
            if (searchMap.get("images") != null && !"".equals(searchMap.get("images"))) {
                criteria.andLike("images", "%" + searchMap.get("images") + "%");
            }
            // SPUID
            if (searchMap.get("spuId") != null && !"".equals(searchMap.get("spuId"))) {
                criteria.andLike("spuId", "%" + searchMap.get("spuId") + "%");
            }
            // 类目名称
            if (searchMap.get("categoryName") != null && !"".equals(searchMap.get("categoryName"))) {
                criteria.andLike("categoryName", "%" + searchMap.get("categoryName") + "%");
            }
            // 品牌名称
            if (searchMap.get("brandName") != null && !"".equals(searchMap.get("brandName"))) {
                criteria.andLike("brandName", "%" + searchMap.get("brandName") + "%");
            }
            // 规格
            if (searchMap.get("spec") != null && !"".equals(searchMap.get("spec"))) {
                criteria.andLike("spec", "%" + searchMap.get("spec") + "%");
            }
            // 商品状态 1-正常，2-下架，3-删除
            if (searchMap.get("status") != null && !"".equals(searchMap.get("status"))) {
                criteria.andLike("status", "%" + searchMap.get("status") + "%");
            }

            // 价格（分）
            if (searchMap.get("price") != null) {
                criteria.andEqualTo("price", searchMap.get("price"));
            }
            // 库存数量
            if (searchMap.get("num") != null) {
                criteria.andEqualTo("num", searchMap.get("num"));
            }
            // 库存预警数量
            if (searchMap.get("alertNum") != null) {
                criteria.andEqualTo("alertNum", searchMap.get("alertNum"));
            }
            // 重量（克）
            if (searchMap.get("weight") != null) {
                criteria.andEqualTo("weight", searchMap.get("weight"));
            }
            // 类目ID
            if (searchMap.get("categoryId") != null) {
                criteria.andEqualTo("categoryId", searchMap.get("categoryId"));
            }
            // 销量
            if (searchMap.get("saleNum") != null) {
                criteria.andEqualTo("saleNum", searchMap.get("saleNum"));
            }
            // 评论数
            if (searchMap.get("commentNum") != null) {
                criteria.andEqualTo("commentNum", searchMap.get("commentNum"));
            }

        }
        return example;
    }

}
